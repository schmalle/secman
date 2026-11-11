package com.secman.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.secman.config.UserCleanupConfig
import com.secman.domain.NotificationEventType
import com.secman.domain.User
import com.secman.domain.UserCleanupOutcome
import com.secman.domain.UserCleanupRun
import com.secman.domain.UserCleanupRunItem
import com.secman.domain.UserCleanupRunStatus
import com.secman.dto.UserCleanupItemDto
import com.secman.dto.UserCleanupRunSummary
import com.secman.event.ChatNotificationEvent
import com.secman.event.ChatNotificationEvent.ChatField
import com.secman.repository.McpApiKeyRepository
import com.secman.repository.UserCleanupRunItemRepository
import com.secman.repository.UserCleanupRunRepository
import com.secman.repository.UserRepository
import io.micronaut.context.event.ApplicationEventPublisher
import io.micronaut.data.model.Pageable
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.util.concurrent.atomic.AtomicBoolean

class UserCleanupInProgressException : RuntimeException("An inactive user cleanup run is already in progress")
class UserCleanupMisconfiguredException(reason: String) : RuntimeException("Inactive user cleanup misconfigured: $reason")

/**
 * Nightly inactive-user cleanup (`docs/USER_CLEANUP.md`): warn users who are close to the
 * inactivity limit, then delete those who were warned long enough ago, through the same
 * [UserDeletionValidator] + [UserService.deleteUser] path as the manual ADMIN delete.
 *
 * Phases always run in this order: select → exclude → warn → brake → delete → record.
 * The run is not @Transactional: each warning is its own update and each deletion its own
 * transaction ([InactiveUserDeletionExecutor]), so mail I/O never holds a transaction.
 */
@Singleton
open class InactiveUserCleanupService(
    private val defaultConfig: UserCleanupConfig,
    private val userRepository: UserRepository,
    private val mcpApiKeyRepository: McpApiKeyRepository,
    private val validator: UserDeletionValidator,
    private val mailer: InactiveUserWarningMailer,
    private val executor: InactiveUserDeletionExecutor,
    private val runRepository: UserCleanupRunRepository,
    private val itemRepository: UserCleanupRunItemRepository,
    private val eventPublisher: ApplicationEventPublisher<ChatNotificationEvent>,
    private val objectMapper: ObjectMapper
) {
    private val log = LoggerFactory.getLogger(InactiveUserCleanupService::class.java)
    private val running = AtomicBoolean(false)

    /** Run and record. [config] defaults to the deployed settings; tests pass their own. */
    open fun run(triggeredBy: String, config: UserCleanupConfig = defaultConfig): UserCleanupRunSummary {
        config.validate()?.let { throw UserCleanupMisconfiguredException(it) }
        if (!running.compareAndSet(false, true)) throw UserCleanupInProgressException()
        val actor = sanitize(triggeredBy)
        val run = runRepository.save(
            UserCleanupRun(triggeredBy = actor, startedAt = Instant.now(), status = UserCleanupRunStatus.FAILED,
                dryRun = config.dryRun, inactiveDays = config.inactiveDays)
        )
        try {
            log.info("Inactive user cleanup started: runId={} triggeredBy={} dryRun={}", run.id, actor, config.dryRun)
            val summary = evaluate(config, live = !config.dryRun).copy(runId = run.id)
            record(run, summary)
            publish(summary)
            log.info(
                "Inactive user cleanup finished: runId={} status={} candidates={} deleted={} warned={} skipped={} errors={}",
                run.id, summary.status, summary.candidateCount, summary.deletedCount, summary.warnedCount,
                summary.skippedCount, summary.errorCount
            )
            return summary.copy(items = emptyList())
        } catch (e: Exception) {
            run.finishedAt = Instant.now()
            runRepository.update(run)
            log.error("Inactive user cleanup failed: runId={} triggeredBy={}", run.id, actor, e)
            throw e
        } finally {
            running.set(false)
        }
    }

    /** What a run would do right now with the deployed settings, as a dry run. Persists nothing. */
    open fun preview(): UserCleanupRunSummary {
        defaultConfig.validate()?.let { throw UserCleanupMisconfiguredException(it) }
        return evaluate(defaultConfig.copy(dryRun = true), live = false)
    }

    private fun evaluate(config: UserCleanupConfig, live: Boolean): UserCleanupRunSummary {
        val now = Instant.now()
        val (candidates, truncated) = loadCandidates(now, config)
        val items = mutableListOf<UserCleanupItemDto>()
        val toWarn = mutableListOf<User>()
        val deletable = mutableListOf<User>()

        for (user in candidates) {
            val inactive = InactiveUserEligibility.isInactive(user, now, config.inactiveDays)
            // A dry run assumes the warning went out, so it shows the eventual impact.
            val action = when {
                inactive && (!live || InactiveUserEligibility.hasValidWarning(user, now, config.warningDays)) -> deletable
                InactiveUserEligibility.needsWarning(user, now, config) -> toWarn
                inactive -> null
                else -> continue // warned, waiting out the warning period: nothing to do tonight
            }
            // Exclusions are checked only for users the run would act on; the validator is the
            // expensive part. Excluded users are never warned about a deletion that cannot happen.
            val exclusion = exclusionReason(user, config)
            when {
                exclusion != null -> items += item(user, UserCleanupOutcome.SKIPPED, exclusion)
                action == null -> items += item(user, UserCleanupOutcome.SKIPPED, REASON_NOT_WARNED)
                else -> action += user
            }
        }

        toWarn.take(config.maxUsersPerRun).forEach { items += warn(it, now, config, live) }
        toWarn.drop(config.maxUsersPerRun).forEach { items += item(it, UserCleanupOutcome.SKIPPED, REASON_RUN_CAP) }

        val brakeTripped = deletable.size.toLong() * 100 > config.maxDeletePercent.toLong() * userRepository.count()
        when {
            !live -> deletable.forEach { items += item(it, UserCleanupOutcome.WOULD_DELETE) }
            brakeTripped -> {
                log.warn("Inactive user cleanup brake tripped: deletable={} maxDeletePercent={}", deletable.size, config.maxDeletePercent)
                deletable.forEach { items += item(it, UserCleanupOutcome.SKIPPED, REASON_BRAKE) }
            }
            else -> {
                deletable.take(config.maxUsersPerRun).forEach { items += delete(it, now, config) }
                deletable.drop(config.maxUsersPerRun).forEach { items += item(it, UserCleanupOutcome.SKIPPED, REASON_RUN_CAP) }
            }
        }

        val status = when {
            !live -> UserCleanupRunStatus.DRY_RUN
            brakeTripped -> UserCleanupRunStatus.ABORTED_BRAKE
            else -> UserCleanupRunStatus.COMPLETED
        }
        return summarize(status, !live, candidates.size, brakeTripped, truncated, items)
            .copy(noActivityBaselineCount = userRepository.countNoActivityBaseline())
    }

    /** Users inside the warning window, longest-inactive first, bounded by [MAX_CANDIDATES]. */
    private fun loadCandidates(now: Instant, config: UserCleanupConfig): Pair<List<User>, Boolean> {
        val cutoff = now.minus(Duration.ofDays((config.inactiveDays - config.warningDays).toLong()))
        val ids = mutableListOf<Long>()
        var page = 0
        while (ids.size < MAX_CANDIDATES) {
            val batch = userRepository.findIdsWithActivityBefore(cutoff, Pageable.from(page++, PAGE_SIZE))
            ids += batch
            if (batch.size < PAGE_SIZE) break
        }
        val truncated = ids.size >= MAX_CANDIDATES
        val users = ids.take(MAX_CANDIDATES).chunked(PAGE_SIZE).flatMap { chunk ->
            userRepository.findByIdIn(chunk).sortedBy { chunk.indexOf(it.id) }
        }
        return users to truncated
    }

    private fun exclusionReason(user: User, config: UserCleanupConfig): String? {
        val keyCutoff = LocalDateTime.now().minusDays(config.inactiveDays.toLong())
        return when {
            user.serviceAccount -> REASON_SERVICE_ACCOUNT
            user.isAdmin() -> REASON_ADMIN
            mcpApiKeyRepository.findByUserId(user.id!!).any { it.lastUsedAt?.isAfter(keyCutoff) == true } -> REASON_ACTIVE_API_KEY
            !validator.validateUserDeletion(user.id!!).canDelete -> REASON_BLOCKING_REFERENCES
            else -> null
        }
    }

    private fun warn(user: User, now: Instant, config: UserCleanupConfig, live: Boolean): UserCleanupItemDto {
        if (!live) return item(user, UserCleanupOutcome.WOULD_WARN)
        if (!mailer.send(user, InactiveUserEligibility.deletionDate(user, now, config))) {
            return item(user, UserCleanupOutcome.WARNING_FAILED)
        }
        // Targeted update: saving the loaded entity could overwrite a login made since it was read.
        userRepository.markInactivityWarningSent(user.id!!, now)
        return item(user, UserCleanupOutcome.WARNED)
    }

    private fun delete(user: User, now: Instant, config: UserCleanupConfig): UserCleanupItemDto = try {
        executor.deleteIfStillEligible(user.id!!, now, config).also {
            if (it.outcome == UserCleanupOutcome.DELETED) {
                log.info("Inactive user deleted: target userId={} outcome=DELETED", user.id)
            }
        }
    } catch (e: Exception) {
        log.error("Inactive user deletion failed: target userId={}", user.id, e)
        item(user, UserCleanupOutcome.ERROR, e.javaClass.simpleName)
    }

    private fun item(user: User, outcome: UserCleanupOutcome, reason: String? = null) =
        UserCleanupItemDto(user.id!!, user.username, user.email, InactiveUserEligibility.lastActivity(user), outcome, reason)

    private fun summarize(
        status: UserCleanupRunStatus, dryRun: Boolean, candidates: Int, brakeTripped: Boolean,
        truncated: Boolean, items: List<UserCleanupItemDto>
    ): UserCleanupRunSummary {
        fun count(vararg outcomes: UserCleanupOutcome) = items.count { it.outcome in outcomes }
        fun detailIds(key: String) = items.flatMap { it.details[key].orEmpty() }.distinct()
        return UserCleanupRunSummary(
            runId = null, status = status, dryRun = dryRun, candidateCount = candidates,
            deletedCount = count(UserCleanupOutcome.DELETED, UserCleanupOutcome.WOULD_DELETE),
            skippedCount = count(UserCleanupOutcome.SKIPPED),
            warnedCount = count(UserCleanupOutcome.WARNED, UserCleanupOutcome.WOULD_WARN),
            errorCount = count(UserCleanupOutcome.ERROR, UserCleanupOutcome.WARNING_FAILED),
            brakeTripped = brakeTripped, inputTruncated = truncated,
            orphanedWorkgroupIds = detailIds("orphanedWorkgroupIds"),
            staleOwnerWorkgroupIds = detailIds("staleOwnerWorkgroupIds"),
            items = items
        )
    }

    private fun record(run: UserCleanupRun, summary: UserCleanupRunSummary) {
        itemRepository.saveAll(summary.items.map {
            UserCleanupRunItem(
                runId = run.id!!, userId = it.userId, username = it.username, email = it.email,
                lastActivityAt = it.lastActivityAt, outcome = it.outcome, reason = it.reason,
                detailsJson = it.details.takeIf { d -> d.isNotEmpty() }?.let(objectMapper::writeValueAsString)
            )
        })
        run.status = summary.status
        run.finishedAt = Instant.now()
        run.candidateCount = summary.candidateCount
        run.deletedCount = summary.deletedCount
        run.skippedCount = summary.skippedCount
        run.warnedCount = summary.warnedCount
        run.errorCount = summary.errorCount
        run.summaryJson = objectMapper.writeValueAsString(
            mapOf(
                "brakeTripped" to summary.brakeTripped,
                "inputTruncated" to summary.inputTruncated,
                "noActivityBaselineCount" to summary.noActivityBaselineCount,
                "orphanedWorkgroupIds" to summary.orphanedWorkgroupIds,
                "staleOwnerWorkgroupIds" to summary.staleOwnerWorkgroupIds
            )
        )
        runRepository.update(run)
    }

    /** Counts only: chat channels are not the place for the names of deleted accounts. */
    private fun publish(summary: UserCleanupRunSummary) {
        try {
            eventPublisher.publishEvent(
                ChatNotificationEvent(
                    eventType = NotificationEventType.INACTIVE_USER_CLEANUP_COMPLETED,
                    title = "Inactive user cleanup finished",
                    summary = if (summary.brakeTripped && !summary.dryRun)
                        "The safety brake stopped all deletions; check the run before raising max-delete-percent." else "",
                    fields = listOf(
                        ChatField("Status", summary.status.name),
                        ChatField(if (summary.dryRun) "Would delete" else "Deleted", summary.deletedCount.toString()),
                        ChatField(if (summary.dryRun) "Would warn" else "Warned", summary.warnedCount.toString()),
                        ChatField("Skipped", summary.skippedCount.toString()),
                        ChatField("Errors", summary.errorCount.toString()),
                        ChatField("Workgroups left without members", summary.orphanedWorkgroupIds.size.toString())
                    )
                )
            )
        } catch (e: Exception) {
            log.warn("Could not publish inactive user cleanup notification: {}", e.javaClass.simpleName)
        }
    }

    private fun sanitize(value: String): String = value.replace(Regex("[\\r\\n]"), "_").take(255)

    companion object {
        const val REASON_SERVICE_ACCOUNT = "SERVICE_ACCOUNT"
        const val REASON_ADMIN = "ADMIN"
        const val REASON_ACTIVE_API_KEY = "ACTIVE_API_KEY"
        const val REASON_BLOCKING_REFERENCES = "BLOCKING_REFERENCES"
        const val REASON_NOT_WARNED = "NOT_WARNED"
        const val REASON_ACTIVE_AGAIN = "ACTIVE_AGAIN"
        const val REASON_BRAKE = "SAFETY_BRAKE"
        const val REASON_RUN_CAP = "RUN_CAP"
        const val REASON_GONE = "USER_GONE"
        private const val PAGE_SIZE = 200
        private const val MAX_CANDIDATES = 20_000
    }
}
