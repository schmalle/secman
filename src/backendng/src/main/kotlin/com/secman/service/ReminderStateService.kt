package com.secman.service

import com.secman.domain.AssetReminderState
import com.secman.repository.AssetReminderStateRepository
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Service for managing reminder state progression (level 1 → level 2)
 * Feature 035: Outdated Asset Notification System
 */
@Singleton
class ReminderStateService(
    private val assetReminderStateRepository: AssetReminderStateRepository
) {
    private val logger = LoggerFactory.getLogger(ReminderStateService::class.java)

    companion object {
        const val ESCALATION_THRESHOLD_DAYS = 7L
    }

    /**
     * Get or create reminder state for an asset
     * If asset is newly outdated, create level 1 state
     * If asset has been outdated for 7+ days, escalate to level 2
     *
     * @param assetId Asset ID
     * @param currentlyOutdated Whether asset is currently in outdated view
     * @return Reminder state or null if asset is no longer outdated
     */
    fun getOrCreateReminderState(assetId: Long, currentlyOutdated: Boolean): AssetReminderState? {
        val existing = assetReminderStateRepository.findByAssetId(assetId)

        return if (currentlyOutdated) {
            if (existing.isPresent) {
                val state = existing.get()

                // Check if should escalate to level 2
                val daysSinceOutdated = ChronoUnit.DAYS.between(state.outdatedSince, Instant.now())
                if (state.level == 1 && daysSinceOutdated >= ESCALATION_THRESHOLD_DAYS) {
                    // Escalate to level 2
                    logger.info("Escalating reminder for asset $assetId to level 2 (outdated for $daysSinceOutdated days)")
                    val escalated = state.copy(
                        level = 2,
                        lastCheckedAt = Instant.now()
                    )
                    assetReminderStateRepository.update(escalated)
                    escalated
                } else {
                    // Update last checked time
                    state.lastCheckedAt = Instant.now()
                    assetReminderStateRepository.update(state)
                    state
                }
            } else {
                // Create new level 1 state
                logger.info("Creating new level 1 reminder state for asset $assetId")
                val newState = AssetReminderState(
                    assetId = assetId,
                    level = 1,
                    lastSentAt = Instant.now(), // Will be updated when email is sent
                    outdatedSince = Instant.now(),
                    lastCheckedAt = Instant.now()
                )
                assetReminderStateRepository.save(newState)
            }
        } else {
            // Asset is no longer outdated, reset state
            if (existing.isPresent) {
                logger.info("Asset $assetId is no longer outdated, resetting reminder state")
                assetReminderStateRepository.deleteByAssetId(assetId)
            }
            null
        }
    }

    /**
     * Update last sent timestamp for a reminder state
     */
    fun updateLastSent(assetId: Long) {
        val state = assetReminderStateRepository.findByAssetId(assetId)
        if (state.isPresent) {
            val updated = state.get().copy(lastSentAt = Instant.now())
            assetReminderStateRepository.update(updated)
        }
    }

    /**
     * Check if notification should be sent today
     * Prevents duplicate sends within same day
     */
    fun shouldSendToday(assetId: Long): Boolean {
        val state = assetReminderStateRepository.findByAssetId(assetId)
        if (state.isEmpty) return true

        val lastSent = state.get().lastSentAt
        val today = Instant.now().truncatedTo(ChronoUnit.DAYS)
        val lastSentDay = lastSent.truncatedTo(ChronoUnit.DAYS)

        return today != lastSentDay
    }

    /**
     * Reset reminder state for an asset (when it becomes up-to-date)
     */
    fun resetState(assetId: Long) {
        assetReminderStateRepository.deleteByAssetId(assetId)
        logger.info("Reset reminder state for asset $assetId")
    }
}
