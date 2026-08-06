package com.secman.repository.projection

import io.micronaut.core.annotation.Introspected
import io.micronaut.serde.annotation.Serdeable

/**
 * Row representation for native affected assets by CVE queries.
 *
 * Used in CVE drilldown to show which systems are affected.
 */
@Serdeable
@Introspected
data class AffectedAssetRow(
    val assetId: Long?,
    val assetName: String?,
    val assetIp: String?,
    val adDomain: String?,
    val assetType: String?
)
