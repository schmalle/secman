package com.secman.repository

import com.secman.domain.TelegramConfig
import io.micronaut.data.annotation.Repository
import io.micronaut.data.jpa.repository.JpaRepository

/**
 * Repository for the workspace-level Telegram configuration.
 *
 * Only the lowest-id row is meaningful; see [com.secman.service.TelegramConfigService].
 */
@Repository
interface TelegramConfigRepository : JpaRepository<TelegramConfig, Long>
