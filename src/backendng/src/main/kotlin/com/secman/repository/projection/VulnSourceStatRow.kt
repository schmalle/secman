package com.secman.repository.projection

import io.micronaut.core.annotation.Introspected
import io.micronaut.serde.annotation.Serdeable
import java.math.BigInteger
import java.time.LocalDateTime

/**
 * Per-`source` vulnerability statistics for a single asset, used by the host
 * diagnostic endpoint to show how many rows are persisted, from which importer,
 * and the import-timestamp window.
 *
 * Diagnoses the EC2AMAZ-3CSRJ2O class of glitch: an empty result here (no rows)
 * for an asset that has live Falcon findings pinpoints the persisted-vs-live gap.
 */
@Serdeable
@Introspected
data class VulnSourceStatRow(
    val source: String?,
    val count: BigInteger?,
    val minImport: LocalDateTime?,
    val maxImport: LocalDateTime?
)
