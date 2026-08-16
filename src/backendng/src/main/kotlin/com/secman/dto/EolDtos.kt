package com.secman.dto

import com.secman.domain.EolStatus
import com.secman.domain.EolSubjectType
import io.micronaut.serde.annotation.Serdeable
import java.time.Instant
import java.time.LocalDate

/** One EOL / approaching-EOL component as shown in the UI and exports. */
@Serdeable
data class EolFindingResponse(
    val id: Long,
    val subjectType: EolSubjectType,
    val assetId: Long?,
    val assetName: String?,
    val cloudAccountId: String?,
    val adDomain: String?,
    val assetOwner: String?,
    val repositoryId: Long?,
    val repositoryFullName: String?,
    val componentName: String,
    val componentVendor: String?,
    val componentVersion: String?,
    val ecosystem: String?,
    val productKey: String,
    val cycle: String,
    val eolDate: LocalDate?,
    val status: EolStatus,
    val daysUntilEol: Long?,
    val detectedAt: Instant?
)

@Serdeable
data class EolFindingListResponse(
    val findings: List<EolFindingResponse>,
    val total: Long,
    val page: Int,
    val pageSize: Int
)

@Serdeable
data class EolAccountSummary(
    val cloudAccountId: String,
    val eolCount: Long,
    val approachingCount: Long
)

@Serdeable
data class EolComponentSummary(
    val componentName: String,
    val productKey: String,
    val cycle: String,
    val status: EolStatus,
    val affectedAssets: Long
)

/** Aggregates for the caller's own scope — never global unless the caller is ADMIN/SECCHAMPION. */
@Serdeable
data class EolSummaryResponse(
    val eolCount: Long,
    val approachingCount: Long,
    val affectedAssets: Long,
    val horizonMonths: Long,
    val accounts: List<EolAccountSummary>,
    val topComponents: List<EolComponentSummary>,
    val lastScanAt: Instant?
)

@Serdeable
data class EolRepositoryRankResponse(
    val rank: Int,
    val repositoryId: Long,
    val fullName: String,
    val distinctEolComponents: Long,
    val eolFindings: Long,
    val approachingFindings: Long
)

@Serdeable
data class EolTopRepositoriesResponse(
    val repositories: List<EolRepositoryRankResponse>,
    val limit: Int
)

@Serdeable
data class EolCatalogStatusResponse(
    val sourceKey: String,
    val products: Long,
    val releases: Long,
    val findings: Long,
    val lastSyncStatus: String?,
    val lastSyncAt: Instant?,
    val lastSyncTriggeredBy: String?,
    val lastSyncError: String?
)

/** Request body for `POST /api/eol/catalog/sync`. */
@Serdeable
data class EolSyncRequest(
    /** Restrict the sync to these upstream product keys; empty = full catalogue. */
    val products: List<String> = emptyList(),
    /** Run the matching scan after the download (default true). */
    val scan: Boolean = true,
    /** Skip the download and only re-run the matching scan. */
    val scanOnly: Boolean = false,
    val horizonMonths: Long? = null
)

@Serdeable
data class EolSyncResponse(
    val runId: String,
    val status: String,
    val productsRequested: Int,
    val productsSynced: Int,
    val releasesSynced: Int,
    val productsFailed: List<String>,
    val assetsScanned: Int,
    val repositoriesScanned: Int,
    val findingsWritten: Int,
    val eolFindings: Int,
    val approachingFindings: Int,
    val findingsRemoved: Int,
    val errorSummary: String?
)

/** Request body for `POST /api/eol/notifications/send`. */
@Serdeable
data class EolNotificationRequest(
    /** Notify about components going EOL within this many months (default 12). */
    val months: Long = 12,
    /** Resolve recipients and findings as usual but send no mail; see [EolNotificationRecipientResult.sent]. */
    val dryRun: Boolean = false,
    /**
     * Restrict delivery to one recipient address (case-insensitive), e.g. to test
     * a specific account owner's notification without mailing the whole run.
     * Findings are still resolved for every recipient; every other recipient is
     * simply dropped before send, so [EolNotificationResponse.recipientsResolved]
     * reflects only the restricted set.
     */
    val onlyEmail: String? = null,
    /** Include components already past EOL alongside the upcoming ones. */
    val includeAlreadyEol: Boolean = false
)

@Serdeable
data class EolNotificationRecipientResult(
    val email: String,
    val componentCount: Int,
    val assetCount: Int,
    val sent: Boolean,
    val failureReason: String?
)

@Serdeable
data class EolNotificationResponse(
    val status: String,
    val months: Long,
    val dryRun: Boolean,
    val findingsConsidered: Int,
    val recipientsResolved: Int,
    val emailsSent: Int,
    val emailsFailed: Int,
    val unmappedOwners: List<String>,
    val recipients: List<EolNotificationRecipientResult>
)
