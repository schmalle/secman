package com.secman.service

import com.secman.config.UserCleanupConfig
import com.secman.domain.User
import com.secman.repository.McpApiKeyRepository
import jakarta.inject.Singleton
import java.time.LocalDateTime

/**
 * Who the inactive-user cleanup must never warn or delete. Checked at selection and again
 * inside the locked deletion transaction, because roles, flags and key use can change during
 * a run whose warning phase may take a long time.
 */
@Singleton
open class InactiveUserExclusions(
    private val mcpApiKeyRepository: McpApiKeyRepository,
    private val validator: UserDeletionValidator
) {
    /** The skip reason, or null when the user may be warned or deleted. */
    open fun reason(user: User, config: UserCleanupConfig): String? {
        // last_used_at is written with LocalDateTime.now(), so compare in the same zone.
        val keyCutoff = LocalDateTime.now().minusDays(config.inactiveDays.toLong())
        return when {
            user.serviceAccount -> InactiveUserCleanupService.REASON_SERVICE_ACCOUNT
            user.isAdmin() -> InactiveUserCleanupService.REASON_ADMIN
            mcpApiKeyRepository.findByUserId(user.id!!).any { it.lastUsedAt?.isAfter(keyCutoff) == true } ->
                InactiveUserCleanupService.REASON_RECENT_KEY_USE
            !validator.validateUserDeletion(user.id!!).canDelete -> InactiveUserCleanupService.REASON_BLOCKING_REFERENCES
            else -> null
        }
    }
}
