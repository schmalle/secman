package com.secman.dto

import io.micronaut.serde.annotation.Serdeable
import java.time.Instant

@Serdeable data class IntegrationScannerRequest(val name: String, val source: String, val serviceUserId: Long, val staleAfterHours: Int = 24, val enabled: Boolean = true)
@Serdeable data class IntegrationScannerDto(val id: Long, val name: String, val source: String, val serviceUserId: Long, val staleAfterHours: Int, val enabled: Boolean)
@Serdeable data class IntegrationSubjectRequest(val assetId: Long? = null, val githubRepositoryId: Long? = null)
@Serdeable data class IntegrationRunRequest(
    val scannerId: Long, val subjectId: Long, val runKey: String, val status: String,
    val completeCoverage: Boolean, val startedAt: Instant, val completedAt: Instant,
    val metadataJson: String = "{}", val findings: List<IntegrationFindingInput> = emptyList()
)
@Serdeable data class IntegrationFindingInput(
    val externalId: String, val legacyIds: List<String> = emptyList(), val severity: String, val title: String,
    val description: String? = null, val recommendation: String? = null, val evidence: String? = null,
    val filePath: String? = null, val lineRange: String? = null, val url: String? = null,
    val confidence: Double? = null, val engine: String? = null, val model: String? = null,
    val commitSha: String? = null, val issueUrl: String? = null, val fixPrUrl: String? = null,
    val attachments: List<IntegrationAttachmentInput> = emptyList()
)
@Serdeable data class IntegrationAttachmentInput(val fileName: String, val contentType: String, val base64: String)
@Serdeable data class IntegrationRunAck(val id: Long, val scannerId: Long, val subjectId: Long, val status: String, val accepted: Int, val resolved: Int, val replayed: Boolean)
@Serdeable data class IntegrationPage<T>(val content: List<T>, val totalElements: Long, val totalPages: Int, val number: Int, val size: Int)
@Serdeable data class IntegrationSummaryDto(val scanners: Long, val totalSubjects: Long, val healthySubjects: Long, val failedSubjects: Long, val unscannedSubjects: Long, val staleSubjects: Long, val openFindings: Long)
@Serdeable data class IntegrationSubjectDto(
    val id: Long, val scannerId: Long, val assetId: Long, val githubRepositoryId: Long?,
    val name: String, val uri: String?, val owner: String, val githubInstance: String?, val githubRepoId: Long?,
    val lastStatus: String?, val lastScanAt: Instant?, val lastSuccessfulScanAt: Instant?, val stale: Boolean, val openFindings: Long
)
@Serdeable data class IntegrationRunDto(
    val id: Long, val scannerId: Long, val subjectId: Long, val scannerName: String, val subjectName: String,
    val status: String, val completeCoverage: Boolean, val startedAt: Instant, val completedAt: Instant,
    val accepted: Int, val resolved: Int, val metadataJson: String
)
@Serdeable data class IntegrationAttachmentDto(val id: Long, val fileName: String, val contentType: String)
@Serdeable data class IntegrationRunAttachmentDto(val id: Long, val findingId: Long, val fileName: String, val contentType: String)
@Serdeable data class IntegrationRunDetailDto(val run: IntegrationRunDto, val findings: List<IntegrationFindingInput>, val attachments: List<IntegrationRunAttachmentDto>)
@Serdeable data class IntegrationFindingDto(
    val id: Long, val scannerId: Long, val scannerName: String, val source: String, val subjectId: Long,
    val subjectName: String, val assetId: Long, val githubRepositoryId: Long?, val owner: String,
    val externalId: String, val severity: String, val state: String, val title: String,
    val description: String?, val recommendation: String?, val evidence: String?, val filePath: String?,
    val lineRange: String?, val url: String?, val confidence: Double?, val engine: String?, val model: String?,
    val commitSha: String?, val issueUrl: String?, val fixPrUrl: String?, val firstSeenAt: Instant,
    val lastSeenAt: Instant, val resolvedAt: Instant?, val vulnerabilityId: Long?, val excepted: Boolean,
    val attachments: List<IntegrationAttachmentDto>
)
data class IntegrationFindingFilter(
    val scannerId: Long? = null, val subjectId: Long? = null, val githubRepositoryId: Long? = null,
    val source: String? = null, val owner: String? = null, val severity: String? = null,
    val state: String? = null, val search: String? = null
)
