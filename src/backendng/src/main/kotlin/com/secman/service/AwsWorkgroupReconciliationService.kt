package com.secman.service

import com.secman.domain.WorkgroupAccessChangedEvent
import com.secman.repository.UserRepository
import com.secman.repository.WorkgroupRepository
import com.secman.repository.WorkgroupAwsAccountRepository
import io.micronaut.context.event.ApplicationEventPublisher
import jakarta.inject.Singleton
import jakarta.transaction.Transactional
import org.slf4j.LoggerFactory

/** ADMIN-only callers reconcile relationships; no assets or users are deleted or created. */
@Singleton
open class AwsWorkgroupReconciliationService(
    private val workgroupRepository: WorkgroupRepository,
    private val userRepository: UserRepository,
    private val workgroupService: WorkgroupService,
    private val publisher: ApplicationEventPublisher<WorkgroupAccessChangedEvent>,
    private val accounts: WorkgroupAwsAccountRepository,
    private val safety: CatchAllWorkgroupSafetyService
) {
    private val log = LoggerFactory.getLogger(javaClass)

    data class Outcome(
        val memberOutcome: String = "NO_MATCH",
        val assetsRemoved: Long = 0,
        val emptyMembership: Boolean = false,
        val statusOutcome: String = "NOT_EVALUATED",
        val statusReason: String = ""
    )

    @Transactional
    open fun reconcile(workgroupId: Long?, ownerEmail: String?, actorId: Long?, dryRun: Boolean, expectedAccountId: String): Outcome {
        require(workgroupId != null || dryRun)
        val workgroup = workgroupId?.let { workgroupRepository.findById(it).orElseThrow() }
        val owner = ownerEmail?.let { userRepository.findByEmailIgnoreCase(it).orElse(null) }
        val member = owner?.id?.let { userRepository.findByIdWithWorkgroups(it).orElseThrow() }
        val alreadyMember = workgroupId != null && member?.workgroups?.any { it.id == workgroupId } == true
        val outcome = when {
            member == null -> "NO_MATCH"
            alreadyMember -> "ALREADY_MEMBER"
            dryRun -> "WOULD_ADD"
            else -> "ADDED"
        }
        val assets = 0L // Explicit asset assignments are never owned by this reconciliation.
        val wasEnabled = workgroup?.enabled ?: false
        if (!dryRun && workgroup != null && workgroupId != null) {
            if (member != null && !alreadyMember) {
                workgroupService.assignUsersToWorkgroup(workgroupId, listOf(member.id!!))
            }
            workgroup.awsAccountManaged = true
        }
        // Writes use persisted relationships; previews include only changes this import would make.
        val userCount = (workgroupId?.let { workgroupRepository.countUsersByWorkgroupId(it) } ?: 0L) +
            if (dryRun && member != null && !alreadyMember) 1L else 0L
        val ownerPresent = !(workgroup?.ownerEmail
            ?.takeIf { it.isNotBlank() } ?: ownerEmail?.takeIf { dryRun }).isNullOrBlank()
        val accountIds = workgroupId?.let { accounts.findByWorkgroupId(it).map { account -> account.awsAccountId } }.orEmpty()
        val resolvedAccounts = if (dryRun && expectedAccountId !in accountIds) accountIds + expectedAccountId else accountIds
        val reason = when {
            !ownerPresent -> "MISSING_OWNER"
            userCount == 0L -> "NO_MEMBERS"
            resolvedAccounts != listOf(expectedAccountId) -> "ACCOUNT_MISMATCH"
            safety.exceedsMembershipLimit(userCount) -> "MEMBERSHIP_LIMIT"
            else -> "READY"
        }
        val enabled = reason == "READY"
        val status = when {
            enabled == wasEnabled -> if (enabled) "UNCHANGED_ENABLED" else "UNCHANGED_DISABLED"
            dryRun -> if (enabled) "WOULD_ENABLE" else "WOULD_DISABLE"
            else -> if (enabled) "ENABLED" else "DISABLED"
        }
        if (!dryRun && workgroup != null && workgroupId != null) {
            workgroup.enabled = enabled
            workgroupRepository.update(workgroup)
            publisher.publishEvent(WorkgroupAccessChangedEvent(setOf(workgroupId)))
        }
        log.info("AUDIT: operation=RECONCILE_AWS_WORKGROUP, actorId={}, workgroupId={}, dryRun={}, memberOutcome={}, assetsRemoved={}, status={}, reason={}",
            actorId, workgroupId, dryRun, outcome, assets, status, reason)
        return Outcome(outcome, assets, userCount == 0L, status, reason)
    }
}
