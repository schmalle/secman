package com.secman.repository.projection

import io.micronaut.core.annotation.Introspected
import io.micronaut.serde.annotation.Serdeable
import java.math.BigInteger

/**
 * Row representation for native severity distribution queries.
 *
 * We rely on Micronaut Data to materialize the native query into this DTO,
 * avoiding runtime Map/TypeConverter issues while keeping conversion logic type-safe.
 *
 * Feature: 036-vuln-stats-lense
 */
@Serdeable
@Introspected
data class SeverityDistributionRow(
    val severity: String?,
    val count: BigInteger?
)
