package com.secman.dto

import io.micronaut.serde.annotation.Serdeable
import java.time.Instant

/**
 * Result of comparing two releases
 */
@Serdeable
data class ComparisonResult(
    val fromRelease: ReleaseInfo,
    val toRelease: ReleaseInfo,
    val added: List<RequirementSnapshotSummary>,
    val deleted: List<RequirementSnapshotSummary>,
    val modified: List<RequirementDiff>,
    val unchanged: Int
)

/**
 * Basic release information for comparison
 */
@Serdeable
data class ReleaseInfo(
    val id: Long,
    val version: String,
    val name: String,
    val createdAt: Instant
)

/**
 * Summary of a requirement snapshot (for added/deleted lists)
 */
@Serdeable
data class RequirementSnapshotSummary(
    val id: Long,
    val originalRequirementId: Long,
    val internalId: String,
    val revision: Int,
    val idRevision: String,
    val shortreq: String,
    val chapter: String?,
    val norm: String?,
    val details: String?,
    val motivation: String?,
    val example: String?,
    val usecase: String?,
    val language: String?
)

/**
 * Diff for a modified requirement
 */
@Serdeable
data class RequirementDiff(
    val id: Long,  // originalRequirementId
    val originalRequirementId: Long?,
    val internalId: String,
    val oldRevision: Int,
    val newRevision: Int,
    val shortreq: String,
    val chapter: String?,
    val norm: String?,
    val changes: List<FieldChange>
)

/**
 * Field-level change in a requirement
 */
@Serdeable
data class FieldChange(
    val fieldName: String,
    val oldValue: String?,
    val newValue: String?
)
