package com.secman.dto

import com.fasterxml.jackson.annotation.JsonInclude
import io.micronaut.serde.annotation.Serdeable
import java.time.Instant

@Serdeable @JsonInclude(JsonInclude.Include.ALWAYS) data class AiBuddyCapabilities(
    val schemaVersion: Int = 1, val eligible: Boolean, val roles: List<String>,
    val available: Boolean, val unavailableReason: String?, val disclosureVersion: String = "1",
    val enabled: Boolean = available, val inventoryInputSupported: Boolean = true
)
@Serdeable @JsonInclude(JsonInclude.Include.ALWAYS) data class AiBuddyError(val code: String, val retryable: Boolean = false, val retryAfterSeconds: Long? = null)
@Serdeable @JsonInclude(JsonInclude.Include.ALWAYS) data class AiBuddyStartResponse(
    val schemaVersion: Int = 1, val jobId: String, val state: String,
    val createdAt: Instant, val expiresAt: Instant? = null, val pollAfterMs: Int = 2000
)
@Serdeable @JsonInclude(JsonInclude.Include.ALWAYS) data class AiBuddyJobStatus(
    val schemaVersion: Int = 1, val jobId: String, val state: String, val stage: String,
    val completedBatches: Int, val totalBatches: Int, val createdAt: Instant,
    val startedAt: Instant?, val finishedAt: Instant?, val expiresAt: Instant?,
    val error: AiBuddyError?, val reportAvailable: Boolean
)
@Serdeable @JsonInclude(JsonInclude.Include.ALWAYS) data class AiBuddySystem(
    val assetId: Long, val displayName: String, val osNameVersion: String?,
    val build: String? = null, val edition: String? = null, val architecture: String? = null
)
@Serdeable @JsonInclude(JsonInclude.Include.ALWAYS) data class AiBuddySnapshotInfo(
    val capturedAt: Instant, val fingerprint: String, val assetLastSeen: Instant?,
    val sensorLastSeen: Instant?, val vulnerabilityImportFrom: Instant?, val vulnerabilityImportTo: Instant?,
    val inventoryImportedAt: Instant?, val evidenceRetrievedAt: Instant?,
    val sourceChanged: Boolean = false, val stale: Boolean = false, val staleReasons: List<String> = emptyList()
)
@Serdeable @JsonInclude(JsonInclude.Include.ALWAYS) data class AiBuddyCount(val reason: String, val count: Int)
@Serdeable @JsonInclude(JsonInclude.Include.ALWAYS) data class AiBuddySourceCount(val source: String, val count: Int)
@Serdeable @JsonInclude(JsonInclude.Include.ALWAYS) data class AiBuddyScope(
    val basis: String = "RECORDED_HIGH_CRITICAL_ASSET_FINDINGS", val tableFiltersApplied: Boolean = false,
    val retainedFindingCount: Int, val consideredFindingCount: Int, val uniqueCveCount: Int,
    val nonCveFindingCount: Int, val duplicateRowCount: Int, val exceptedFindingCount: Int,
    val excludedFindingCount: Int, val exclusions: List<AiBuddyCount>,
    val sourceCompleteness: String = "RECORDED_ONLY", val sourceCounts: List<AiBuddySourceCount>
)
@Serdeable @JsonInclude(JsonInclude.Include.ALWAYS) data class AiBuddyAnalysis(
    val completeness: String, val model: String?, val promptVersion: String = "001",
    val evidenceVersion: String, val verifiedCoveredFindingCount: Int,
    val conditionalFindingCount: Int, val noVerifiedRemediationCount: Int, val limitations: List<String>
)
enum class AiBuddyActionType { OS_UPDATE, APPLICATION_UPDATE, CONFIGURATION_CHANGE, FIRMWARE_DRIVER_UPDATE, PRODUCT_UPGRADE, MITIGATION, INVESTIGATION }
enum class AiBuddyEvidenceState { VERIFIED, REQUIRES_APPLICABILITY_CHECK, INSUFFICIENT_INFORMATION }
enum class AiBuddyReboot { REQUIRED, NOT_REQUIRED, CONDITIONAL, UNKNOWN }
@Serdeable @JsonInclude(JsonInclude.Include.ALWAYS) data class AiBuddyCheck(
    val code: String, val value: String? = null, val evidenceRefs: List<String> = emptyList(),
    val required: Boolean = true, val satisfied: Boolean? = null
)
@Serdeable @JsonInclude(JsonInclude.Include.ALWAYS) data class AiBuddyAction(
    val id: String, val type: AiBuddyActionType, val order: Int, val evidenceState: AiBuddyEvidenceState,
    val targetProduct: String?, val patchIds: List<String>, val targetVersion: String?,
    val applicability: List<AiBuddyCheck>, val prerequisites: List<AiBuddyCheck>,
    val dependsOnActionIds: List<String>, val coveredFindingRefs: List<String>, val coveredCves: List<String>,
    val coverageKind: String, val reboot: AiBuddyReboot, val evidenceRefs: List<String>, val explanationCode: String
)
@Serdeable @JsonInclude(JsonInclude.Include.ALWAYS) data class AiBuddyFinding(
    val ref: String, val source: String, val identifier: String?, val product: String?,
    val duplicateCount: Int = 1, val excepted: Boolean, val disposition: String,
    val actionRefs: List<String>, val reasonCodes: List<String>
)
@Serdeable @JsonInclude(JsonInclude.Include.ALWAYS) data class AiBuddyEvidence(
    val ref: String, val source: String, val title: String, val url: String?,
    val retrievedAt: Instant, val publishedAt: Instant?, val revision: String?, val supports: List<String>,
    val providerAdvice: String? = null
)
@Serdeable @JsonInclude(JsonInclude.Include.ALWAYS) data class AiBuddyObservation(
    val actionRef: String, val installationState: String = "UNKNOWN", val observedAt: Instant? = null,
    val evidenceRefs: List<String> = emptyList(), val vulnerabilityState: String = "STILL_RECORDED"
)
@Serdeable @JsonInclude(JsonInclude.Include.ALWAYS) data class AiBuddyMissingInfo(val code: String, val findingRefs: List<String>, val checkCode: String)
@Serdeable @JsonInclude(JsonInclude.Include.ALWAYS) data class AiBuddyPostCheck(val code: String, val actionRefs: List<String>)
@Serdeable @JsonInclude(JsonInclude.Include.ALWAYS) data class AiBuddyReport(
    val schemaVersion: Int = 1, val jobId: String, val generatedAt: Instant, val expiresAt: Instant,
    val system: AiBuddySystem, val snapshot: AiBuddySnapshotInfo, val scope: AiBuddyScope,
    val analysis: AiBuddyAnalysis, val actions: List<AiBuddyAction>, val findings: List<AiBuddyFinding>,
    val evidence: List<AiBuddyEvidence>, val remediationObservations: List<AiBuddyObservation>,
    val missingInformation: List<AiBuddyMissingInfo>, val postRemediationChecks: List<AiBuddyPostCheck>
)
