package com.secman.repository

import com.secman.domain.NotificationPreference
import io.micronaut.data.annotation.Repository
import io.micronaut.data.jpa.repository.JpaRepository
import java.util.Optional

/**
 * Repository for NotificationPreference entity
 * Feature 035: Outdated Asset Notification System
 */
@Repository
interface NotificationPreferenceRepository : JpaRepository<NotificationPreference, Long> {
    /**
     * Find notification preference by user ID
     */
    fun findByUserId(userId: Long): Optional<NotificationPreference>

    /**
     * Find all users who have enabled new vulnerability notifications
     */
    fun findByEnableNewVulnNotificationsTrue(): List<NotificationPreference>
}
