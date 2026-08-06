package com.secman.repository

import com.secman.domain.AdminNotificationSettings
import io.micronaut.data.annotation.Query
import io.micronaut.data.annotation.Repository
import io.micronaut.data.jpa.repository.JpaRepository
import java.util.*

/**
 * Repository for AdminNotificationSettings
 * Feature: 027-admin-user-notifications
 */
@Repository
interface AdminNotificationSettingsRepository : JpaRepository<AdminNotificationSettings, Long> {

    /**
     * Get the first (and should be only) settings record
     * There should only ever be one row in this table
     */
    @Query("SELECT ans FROM AdminNotificationSettings ans ORDER BY ans.id ASC")
    fun findFirstSettings(): Optional<AdminNotificationSettings>
}
