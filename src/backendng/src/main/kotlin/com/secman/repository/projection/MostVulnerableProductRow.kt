package com.secman.repository.projection

import io.micronaut.core.annotation.Introspected
import io.micronaut.serde.annotation.Serdeable
import java.math.BigInteger

/**
 * Row representation for native most-vulnerable product queries.
 *
 * We rely on Micronaut Data to materialize the native query into this DTO,
 * avoiding runtime Map/TypeConverter issues while keeping conversion logic type-safe.
 */
@Serdeable
@Introspected
data class MostVulnerableProductRow(
    val product: String?,
    val vulnerabilityCount: BigInteger?,
    val affectedAssetCount: BigInteger?,
    val criticalCount: BigInteger?,
    val highCount: BigInteger?
) {
    /** See [MostCommonVulnerabilityRow.toDto] — one mapping, shared by live and cache paths. */
    fun toDto() = com.secman.dto.MostVulnerableProductDto(
        product = product ?: "",
        vulnerabilityCount = vulnerabilityCount?.toLong() ?: 0L,
        affectedAssetCount = affectedAssetCount?.toLong() ?: 0L,
        criticalCount = criticalCount?.toLong() ?: 0L,
        highCount = highCount?.toLong() ?: 0L
    )
}
