package com.secman.repository.projection

import io.micronaut.core.annotation.Introspected
import io.micronaut.serde.annotation.Serdeable
import java.math.BigInteger

/**
 * Row representation for native top-assets-by-vulnerabilities queries.
 *
 * We rely on Micronaut Data to materialize the native query into this DTO,
 * avoiding runtime Map/TypeConverter issues while keeping conversion logic type-safe.
 *
 * Feature: 036-vuln-stats-lense
 */
@Serdeable
@Introspected
data class TopAssetByVulnerabilitiesRow(
    val assetId: BigInteger?,
    val assetName: String?,
    val assetType: String?,
    val assetIp: String?,
    val totalVulnerabilityCount: BigInteger?,
    val criticalCount: BigInteger?,
    val highCount: BigInteger?,
    val mediumCount: BigInteger?,
    val lowCount: BigInteger?
)
