package com.secman.repository.projection

import io.micronaut.core.annotation.Introspected
import java.time.LocalDateTime

@Introspected
data class CrowdStrikeStoredFinding(
    val vulnerabilityId: String?,
    val vulnerableProductVersions: String?,
    val cvssSeverity: String?,
    val scanTimestamp: LocalDateTime,
    val firstSeenAt: LocalDateTime?,
    val patchPublicationDate: LocalDateTime?,
    val cvePublishedDate: LocalDateTime?,
    val noVendorFix: Boolean?,
    val crowdStrikeSnapshotId: Long?,
    val remediationJson: String? = null,
    val assetId: Long? = null
)
