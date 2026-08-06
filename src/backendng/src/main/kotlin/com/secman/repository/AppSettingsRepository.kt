package com.secman.repository

import com.secman.domain.AppSettings
import io.micronaut.data.annotation.Repository
import io.micronaut.data.jpa.repository.JpaRepository

/**
 * Repository for AppSettings entity.
 * Feature: 068-requirements-alignment-process
 */
@Repository
interface AppSettingsRepository : JpaRepository<AppSettings, Long>
