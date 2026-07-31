package com.secman.repository.projection

import io.micronaut.core.annotation.Introspected
import io.micronaut.serde.annotation.Serdeable
import java.math.BigInteger

/**
 * Per-asset vulnerability counts by severity — one row per asset, not one per vulnerability.
 *
 * Exists so callers that only need counts stop materializing `Vulnerability` entities to count
 * them. `DomainVulnsService` did exactly that on an interactive request: it loaded every
 * vulnerability for every asset in the user's AD domains as a managed entity, then used the result
 * solely for `.size` and `.count { severity == ... }`. That scales with the multi-million-row
 * `vulnerability` table — the same shape that ran a 1 GB container out of heap on 2026-07-30.
 */
@Serdeable
@Introspected
data class AssetVulnSeverityCountRow(
    val assetId: Long?,
    val totalCount: BigInteger?,
    val criticalCount: BigInteger?,
    val highCount: BigInteger?,
    val mediumCount: BigInteger?,
    val lowCount: BigInteger?
)
