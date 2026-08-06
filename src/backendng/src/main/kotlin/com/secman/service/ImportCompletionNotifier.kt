package com.secman.service

import com.secman.domain.NotificationEventType
import com.secman.event.ChatNotificationEvent
import com.secman.event.ChatNotificationEvent.ChatField
import io.micronaut.context.annotation.Value
import io.micronaut.context.event.ApplicationEventPublisher
import io.micronaut.scheduling.annotation.Scheduled
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Turns "an import finished" into a [ChatNotificationEvent].
 *
 * The two paths have deliberately different shapes because the imports do:
 *
 *  - **AWS account import** completes in one call, so [awsAccountImportCompleted]
 *    publishes immediately.
 *  - **CrowdStrike import** is a stream of sub-batch HTTP requests (~94 per full CLI run,
 *    across 3 concurrent workers), so there is no well-defined last batch to hook. Each
 *    batch calls [recordCrowdStrikeBatch], which accumulates the run's totals and pushes a
 *    quiet-period deadline out; [sweepCrowdStrikeRun] publishes exactly one event once the
 *    import has actually gone quiet. Same debounce shape, and for the same reason, as
 *    [MaterializedViewRefreshService.requestDeferredRefresh].
 *
 * Server-side debouncing rather than a CLI "I'm done" call keeps this working for older
 * CLI versions, for the `/api/crowdstrike/vulnerabilities/save` path, and for manual callers.
 */
@Singleton
open class ImportCompletionNotifier(
    private val eventPublisher: ApplicationEventPublisher<ChatNotificationEvent>,

    /**
     * Seconds of no CrowdStrike import activity before a run counts as finished. Must
     * comfortably exceed the gap between sub-batches, or one run reports as several.
     */
    @Value("\${secman.notifications.chat.crowdstrike-quiet-period-seconds:180}")
    private val crowdStrikeQuietPeriodSeconds: Long
) {
    private val log = LoggerFactory.getLogger(ImportCompletionNotifier::class.java)

    /** Running totals for the CrowdStrike import currently in flight, if any. */
    private class CrowdStrikeRun(val startedAt: Instant) {
        var lastActivityAt: Instant = startedAt
        var serversProcessed: Int = 0
        var serversCreated: Int = 0
        var serversUpdated: Int = 0
        var vulnerabilitiesImported: Int = 0
        var vulnerabilitiesSkipped: Int = 0
        var errorCount: Int = 0
        var triggeredBy: String? = null
    }

    /** Guarded by [lock] — concurrent import workers all write to it. */
    private var currentRun: CrowdStrikeRun? = null
    private val lock = Any()

    /**
     * Record one CrowdStrike import sub-batch. Cheap and non-blocking: it only updates
     * in-memory counters, never touches the DB or the network.
     */
    open fun recordCrowdStrikeBatch(
        serversProcessed: Int,
        serversCreated: Int,
        serversUpdated: Int,
        vulnerabilitiesImported: Int,
        vulnerabilitiesSkipped: Int,
        errorCount: Int,
        triggeredBy: String?
    ) {
        synchronized(lock) {
            val run = currentRun ?: CrowdStrikeRun(Instant.now()).also { currentRun = it }
            run.lastActivityAt = Instant.now()
            run.serversProcessed += serversProcessed
            run.serversCreated += serversCreated
            run.serversUpdated += serversUpdated
            run.vulnerabilitiesImported += vulnerabilitiesImported
            run.vulnerabilitiesSkipped += vulnerabilitiesSkipped
            run.errorCount += errorCount
            if (triggeredBy != null) run.triggeredBy = triggeredBy
        }
    }

    /**
     * Publish the CrowdStrike completion event once the import has been quiet for
     * [crowdStrikeQuietPeriodSeconds]. Runs unconditionally but returns immediately when
     * no import is in flight.
     */
    @Scheduled(fixedDelay = "30s")
    open fun sweepCrowdStrikeRun() {
        val finished = synchronized(lock) {
            val run = currentRun ?: return
            val quietSeconds = ChronoUnit.SECONDS.between(run.lastActivityAt, Instant.now())
            if (quietSeconds < crowdStrikeQuietPeriodSeconds) return
            currentRun = null
            run
        }

        val durationMinutes = ChronoUnit.MINUTES.between(finished.startedAt, finished.lastActivityAt)
        val fields = mutableListOf(
            ChatField("Servers processed", finished.serversProcessed.toString()),
            ChatField("Assets created", finished.serversCreated.toString()),
            ChatField("Assets updated", finished.serversUpdated.toString()),
            ChatField("Vulnerabilities imported", finished.vulnerabilitiesImported.toString()),
            ChatField("Vulnerabilities skipped", finished.vulnerabilitiesSkipped.toString()),
            ChatField("Duration", "${durationMinutes} min")
        )
        if (finished.errorCount > 0) {
            fields.add(ChatField("Errors", finished.errorCount.toString()))
        }
        finished.triggeredBy?.let { fields.add(ChatField("Triggered by", it)) }

        publish(
            ChatNotificationEvent(
                eventType = NotificationEventType.CROWDSTRIKE_REPORT_COMPLETED,
                title = "New CrowdStrike report completed",
                summary = "${finished.serversProcessed} server(s) processed, " +
                    "${finished.vulnerabilitiesImported} vulnerability record(s) imported.",
                fields = fields
            )
        )
    }

    /**
     * Publish the AWS account import completion event.
     *
     * @param source which entry point ran the import (upload, bulk API, MCP), so a
     *   recipient can tell an operator upload from a scheduled CLI run.
     * @param newAccountIds AWS accounts SecMan had never seen before this import.
     */
    open fun awsAccountImportCompleted(
        source: String,
        triggeredBy: String?,
        processed: Int,
        imported: Int,
        skipped: Int,
        errorCount: Int,
        newAccountIds: List<String> = emptyList()
    ) {
        val fields = mutableListOf(
            ChatField("Source", source),
            ChatField("Mappings processed", processed.toString()),
            ChatField("Mappings imported", imported.toString()),
            ChatField("Mappings skipped", skipped.toString())
        )
        if (errorCount > 0) fields.add(ChatField("Errors", errorCount.toString()))
        if (newAccountIds.isNotEmpty()) {
            fields.add(ChatField("New AWS accounts", newAccountIds.size.toString()))
            // Cap the inline list — an initial load can introduce hundreds of accounts and
            // chat clients truncate long messages, which would hide the counts above it.
            fields.add(
                ChatField(
                    "Account IDs",
                    newAccountIds.take(MAX_LISTED_ACCOUNTS).joinToString(", ") +
                        if (newAccountIds.size > MAX_LISTED_ACCOUNTS) ", … (+${newAccountIds.size - MAX_LISTED_ACCOUNTS} more)" else ""
                )
            )
        }
        triggeredBy?.let { fields.add(ChatField("Triggered by", it)) }

        publish(
            ChatNotificationEvent(
                eventType = NotificationEventType.AWS_ACCOUNT_IMPORT_COMPLETED,
                title = "New AWS account import completed",
                summary = if (newAccountIds.isEmpty()) {
                    "$imported mapping(s) imported, no previously unknown AWS accounts."
                } else {
                    "$imported mapping(s) imported, ${newAccountIds.size} new AWS account(s) discovered."
                },
                fields = fields
            )
        )
    }

    /**
     * Publishing is best-effort: a broken Slack path must never fail the import that
     * just succeeded.
     */
    private fun publish(event: ChatNotificationEvent) {
        try {
            log.info("Publishing chat notification event: {}", event.eventType)
            eventPublisher.publishEvent(event)
        } catch (e: Exception) {
            log.error("Failed to publish chat notification event {}", event.eventType, e)
        }
    }

    companion object {
        private const val MAX_LISTED_ACCOUNTS = 20
    }
}
