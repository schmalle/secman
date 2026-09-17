package com.secman.repository

import com.secman.domain.AppSettings
import io.micronaut.data.annotation.Repository
import io.micronaut.data.jpa.repository.JpaRepository
import java.util.Optional

/**
 * Repository for AppSettings entity.
 */
@Repository
interface AppSettingsRepository : JpaRepository<AppSettings, Long> {
    @io.micronaut.data.annotation.Query(
        value = "SELECT * FROM app_settings ORDER BY id ASC LIMIT 1",
        nativeQuery = true
    )
    fun findFirstSettings(): Optional<AppSettings>
}
