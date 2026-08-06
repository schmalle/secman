package com.secman.repository

import com.secman.domain.UserSlackSettings
import io.micronaut.data.annotation.Repository
import io.micronaut.data.jpa.repository.JpaRepository
import java.util.Optional

/**
 * Repository for per-user Slack delivery settings.
 */
@Repository
interface UserSlackSettingsRepository : JpaRepository<UserSlackSettings, Long> {
    fun findByUserId(userId: Long): Optional<UserSlackSettings>

    fun deleteByUserId(userId: Long)
}
