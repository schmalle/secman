package com.secman.repository

import com.secman.domain.AssetReminderState
import io.micronaut.data.annotation.Repository
import io.micronaut.data.jpa.repository.JpaRepository
import java.time.Instant
import java.util.Optional

/**
 * Repository for AssetReminderState entity
 * Feature 035: Outdated Asset Notification System
 */
@Repository
interface AssetReminderStateRepository : JpaRepository<AssetReminderState, Long> {
    /**
     * Find reminder state by asset ID
     */
    fun findByAssetId(assetId: Long): Optional<AssetReminderState>

    /**
     * Delete reminder state by asset ID (when asset becomes up-to-date)
     */
    fun deleteByAssetId(assetId: Long): Int

    /**
     * Find all reminder states for assets that are still outdated and need escalation
     * (level 1, outdated for 7+ days)
     */
    fun findByLevelAndOutdatedSinceBefore(level: Int, outdatedBefore: Instant): List<AssetReminderState>
}
