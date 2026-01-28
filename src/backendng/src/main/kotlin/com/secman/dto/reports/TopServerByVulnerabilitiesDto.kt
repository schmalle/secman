package com.secman.dto.reports

import io.micronaut.serde.annotation.Serdeable

@Serdeable
data class TopServerByVulnerabilitiesDto(
    val assetId: Long,
    val serverName: String,
    val serverIp: String?,
    val totalVulnerabilityCount: Long,
    val criticalCount: Long,
    val highCount: Long
)
