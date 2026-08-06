package com.secman.repository

import com.secman.domain.UserTelegramSettings
import io.micronaut.data.annotation.Repository
import io.micronaut.data.jpa.repository.JpaRepository
import java.util.Optional

/**
 * Repository for per-user Telegram delivery settings.
 */
@Repository
interface UserTelegramSettingsRepository : JpaRepository<UserTelegramSettings, Long> {
    fun findByUserId(userId: Long): Optional<UserTelegramSettings>

    fun deleteByUserId(userId: Long)
}
