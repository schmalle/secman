package com.secman.repository.projection

import io.micronaut.core.annotation.Introspected
import io.micronaut.serde.annotation.Serdeable
import java.math.BigInteger

/**
 * Row representation for top products by vulnerability count.
 *
 * Feature: 054-products-overview
 */
@Serdeable
@Introspected
data class TopProductRow(
    val product: String?,
    val vulnerabilityCount: BigInteger?
)
