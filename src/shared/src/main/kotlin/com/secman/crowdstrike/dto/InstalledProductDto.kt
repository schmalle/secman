package com.secman.crowdstrike.dto

import io.micronaut.serde.annotation.Serdeable
import java.time.LocalDateTime

/**
 * Installed product/application discovered by CrowdStrike Discover.
 */
@Serdeable
data class InstalledProductDto(
    val externalId: String? = null,
    val hostname: String,
    val aid: String? = null,
    val name: String,
    val vendor: String? = null,
    val version: String? = null,
    val category: String? = null,
    val installationPath: String? = null,
    val installedAt: LocalDateTime? = null,
    val lastUsedAt: LocalDateTime? = null,
    val lastUpdatedAt: LocalDateTime? = null
)
