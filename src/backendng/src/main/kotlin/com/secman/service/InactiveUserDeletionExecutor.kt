package com.secman.service

import com.secman.config.UserCleanupConfig
import com.secman.domain.UserCleanupOutcome
import com.secman.dto.UserCleanupItemDto
import com.secman.repository.AwsAccountSharingRepository
import com.secman.repository.UserRepository
import com.secman.repository.WorkgroupRepository
import jakarta.transaction.Transactional
import jakarta.inject.Singleton
import java.time.Instant

/**
 * Deletes one user in its own transaction, so one failure never rolls back the others and a
 * user who logged in after the candidate list was read is re-checked against committed data.
 */
@Singleton
open class InactiveUserDeletionExecutor(
    private val userRepository: UserRepository,
    private val workgroupRepository: WorkgroupRepository,
    private val awsAccountSharingRepository: AwsAccountSharingRepository,
    private val validator: UserDeletionValidator,
    private val userService: UserService
) {

    /** Re-check against committed data, then delete; returns what happened instead of throwing on a skip. */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    open fun deleteIfStillEligible(userId: Long, now: Instant, config: UserCleanupConfig): UserCleanupItemDto {
        val user = userRepository.findByIdWithWorkgroups(userId).orElse(null)
            ?: return UserCleanupItemDto(userId, "", "", null, UserCleanupOutcome.SKIPPED, InactiveUserCleanupService.REASON_GONE)
        fun item(outcome: UserCleanupOutcome, reason: String? = null, details: Map<String, List<Long>> = emptyMap()) =
            UserCleanupItemDto(userId, user.username, user.email, InactiveUserEligibility.lastActivity(user), outcome, reason, details)

        if (!InactiveUserEligibility.isInactive(user, now, config.inactiveDays) ||
            !InactiveUserEligibility.hasValidWarning(user, now, config.warningDays)) {
            return item(UserCleanupOutcome.SKIPPED, InactiveUserCleanupService.REASON_ACTIVE_AGAIN)
        }
        if (!validator.validateUserDeletion(userId).canDelete) {
            return item(UserCleanupOutcome.SKIPPED, InactiveUserCleanupService.REASON_BLOCKING_REFERENCES)
        }

        // Read what the deletion will leave behind before the rows disappear.
        val orphaned = user.workgroups.mapNotNull { it.id }.filter { workgroupRepository.countUsersByWorkgroupId(it) <= 1 }
        val staleOwner = workgroupRepository.findIdsByOwnerEmail(user.email)
        val sharingCounterparts = awsAccountSharingRepository.findAllWithUsersByInvolvedUserId(userId)
            .mapNotNull { if (it.sourceUser.id == userId) it.targetUser.id else it.sourceUser.id }
            .distinct()

        userService.deleteUser(userId)
        return item(
            UserCleanupOutcome.DELETED,
            details = mapOf(
                "orphanedWorkgroupIds" to orphaned,
                "staleOwnerWorkgroupIds" to staleOwner,
                "awsSharingCounterpartUserIds" to sharingCounterparts
            ).filterValues { it.isNotEmpty() }
        )
    }
}
