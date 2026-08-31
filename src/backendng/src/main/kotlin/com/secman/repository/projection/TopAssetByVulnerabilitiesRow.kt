package com.secman.repository.projection

import io.micronaut.core.annotation.Introspected
import io.micronaut.serde.annotation.Serdeable
import java.math.BigInteger

/**
 * Row representation for native top-assets-by-vulnerabilities queries.
 *
 * We rely on Micronaut Data to materialize the native query into this DTO,
 * avoiding runtime Map/TypeConverter issues while keeping conversion logic type-safe.
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
) {
    /** See [MostCommonVulnerabilityRow.toDto] — one mapping, shared by live and cache paths. */
    fun toDto() = com.secman.dto.TopAssetByVulnerabilitiesDto(
        assetId = assetId?.toLong() ?: 0L,
        assetName = assetName ?: "",
        assetType = assetType,
        assetIp = assetIp,
        totalVulnerabilityCount = totalVulnerabilityCount?.toLong() ?: 0L,
        criticalCount = criticalCount?.toLong() ?: 0L,
        highCount = highCount?.toLong() ?: 0L,
        mediumCount = mediumCount?.toLong() ?: 0L,
        lowCount = lowCount?.toLong() ?: 0L
    )
}
