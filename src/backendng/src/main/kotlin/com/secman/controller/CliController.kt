package com.secman.controller

import com.secman.repository.AssetRepository
import com.secman.repository.OutdatedAssetMaterializedViewRepository
import com.secman.repository.UserMappingRepository
import com.secman.service.AdminSummaryService
import com.secman.service.NotificationService
import com.secman.service.UserMappingStatisticsService
import com.secman.service.UserVulnerabilityNotificationService
import io.micronaut.data.model.Pageable
import io.micronaut.http.HttpResponse
import io.micronaut.http.MediaType
import io.micronaut.http.annotation.Body
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.micronaut.http.annotation.Post
import io.micronaut.http.annotation.Produces
import io.micronaut.security.annotation.Secured
import io.micronaut.security.authentication.Authentication
import io.micronaut.serde.annotation.Serdeable
import org.slf4j.LoggerFactory

/**
 * REST API controller for CLI-specific operations.
 *
 * These endpoints allow the CLI to operate without direct database access,
 * by proxying operations through the backend REST API.
 *
 * Access: All endpoints require ADMIN role.
 */
@Controller("/api/cli")
@Secured("ADMIN")
class CliController(
    private val notificationService: NotificationService,
    private val outdatedAssetRepository: OutdatedAssetMaterializedViewRepository,
    private val assetRepository: AssetRepository,
    private val userMappingRepository: UserMappingRepository,
    private val adminSummaryService: AdminSummaryService,
    private val userVulnerabilityNotificationService: UserVulnerabilityNotificationService,
    private val userMappingStatisticsService: UserMappingStatisticsService
) {
    private val logger = LoggerFactory.getLogger(CliController::class.java)

    // --- Notification Endpoints ---

    @Serdeable
    data class SendNotificationsRequest(
        val dryRun: Boolean = false,
        val verbose: Boolean = false
    )

    @Serdeable
    data class NotificationResultDto(
        val assetsProcessed: Int,
        val emailsSent: Int,
        val failures: Int,
        val skipped: List<String>
    )

    /**
     * POST /api/cli/notifications/send-outdated
     *
     * Sends outdated asset notification emails. Replicates the logic previously
     * in the CLI's NotificationCliService: query outdated assets, resolve owners,
     * and send notification emails.
     */
    @Post("/notifications/send-outdated")
    @Produces(MediaType.APPLICATION_JSON)
    fun sendOutdatedNotifications(
        @Body request: SendNotificationsRequest,
        authentication: Authentication
    ): HttpResponse<NotificationResultDto> {
        logger.info("CLI send-outdated-notifications requested by user: {} (dryRun={})",
            authentication.name, request.dryRun)

        return try {
            val outdatedAssets = queryOutdatedAssets(request.verbose)
            val result = notificationService.processOutdatedAssets(outdatedAssets, request.dryRun)

            HttpResponse.ok(NotificationResultDto(
                assetsProcessed = result.assetsProcessed,
                emailsSent = result.emailsSent,
                failures = result.failures,
                skipped = result.skipped
            ))
        } catch (e: Exception) {
            logger.error("Error sending outdated notifications", e)
            HttpResponse.serverError()
        }
    }

    /**
     * Query outdated assets from materialized view and resolve owner emails.
     * Ported from NotificationCliService.queryOutdatedAssets().
     */
    private fun queryOutdatedAssets(verbose: Boolean): List<NotificationService.OutdatedAssetData> {
        val pageable = Pageable.unpaged()
        val outdatedView = outdatedAssetRepository.findOutdatedAssets(
            workgroupId = null,
            searchTerm = null,
            minSeverity = null,
            adDomain = null,
            pageable = pageable
        )

        val results = mutableListOf<NotificationService.OutdatedAssetData>()

        outdatedView.content.forEach { view ->
            val asset = assetRepository.findById(view.assetId).orElse(null)
            if (asset == null) {
                if (verbose) logger.warn("Asset {} not found, skipping", view.assetId)
                return@forEach
            }

            val userMappings = userMappingRepository.findByAwsAccountId(asset.owner)
            if (userMappings.isEmpty()) {
                if (verbose) logger.warn("No email found for AWS account {}, skipping asset {}", asset.owner, asset.name)
                return@forEach
            }

            val ownerEmail = userMappings.first().email

            val severity = when {
                view.criticalCount > 0 -> "CRITICAL"
                view.highCount > 0 -> "HIGH"
                view.mediumCount > 0 -> "MEDIUM"
                else -> "LOW"
            }

            val criticality = asset.getEffectiveCriticality().name

            results.add(
                NotificationService.OutdatedAssetData(
                    assetId = view.assetId,
                    assetName = view.assetName,
                    assetType = view.assetType,
                    ownerEmail = ownerEmail,
                    vulnerabilityCount = view.totalOverdueCount,
                    oldestVulnDays = view.oldestVulnDays,
                    oldestVulnId = view.oldestVulnId ?: "unknown",
                    severity = severity,
                    criticality = criticality
                )
            )
        }

        logger.info("Mapped {} outdated assets with owner emails", results.size)
        return results
    }

    // --- Admin Summary Endpoints ---

    @Serdeable
    data class SystemStatisticsDto(
        val userCount: Long,
        val vulnerabilityCount: Long,
        val assetCount: Long,
        val vulnerabilityStatisticsUrl: String,
        val topProducts: List<ProductSummaryDto>,
        val topServers: List<ServerSummaryDto>
    )

    @Serdeable
    data class ProductSummaryDto(val name: String, val vulnerabilityCount: Long)

    @Serdeable
    data class ServerSummaryDto(val name: String, val vulnerabilityCount: Long)

    @Serdeable
    data class SendAdminSummaryRequest(
        val dryRun: Boolean = false,
        val verbose: Boolean = false
    )

    @Serdeable
    data class AdminSummaryResultDto(
        val status: String,
        val recipientCount: Int,
        val emailsSent: Int,
        val emailsFailed: Int,
        val recipients: List<String>,
        val failedRecipients: List<String>
    )

    /**
     * GET /api/cli/admin-summary/statistics
     *
     * Returns system statistics for the admin summary email.
     */
    @Get("/admin-summary/statistics")
    @Produces(MediaType.APPLICATION_JSON)
    fun getAdminSummaryStatistics(authentication: Authentication): HttpResponse<SystemStatisticsDto> {
        logger.info("CLI admin-summary statistics requested by user: {}", authentication.name)

        return try {
            val stats = adminSummaryService.getSystemStatistics()
            HttpResponse.ok(SystemStatisticsDto(
                userCount = stats.userCount,
                vulnerabilityCount = stats.vulnerabilityCount,
                assetCount = stats.assetCount,
                vulnerabilityStatisticsUrl = stats.vulnerabilityStatisticsUrl,
                topProducts = stats.topProducts.map { ProductSummaryDto(it.name, it.vulnerabilityCount) },
                topServers = stats.topServers.map { ServerSummaryDto(it.name, it.vulnerabilityCount) }
            ))
        } catch (e: Exception) {
            logger.error("Error fetching admin summary statistics", e)
            HttpResponse.serverError()
        }
    }

    /**
     * POST /api/cli/admin-summary/send
     *
     * Sends admin summary email to all ADMIN/REPORT users.
     */
    @Post("/admin-summary/send")
    @Produces(MediaType.APPLICATION_JSON)
    fun sendAdminSummary(
        @Body request: SendAdminSummaryRequest,
        authentication: Authentication
    ): HttpResponse<AdminSummaryResultDto> {
        logger.info("CLI admin-summary send requested by user: {} (dryRun={})",
            authentication.name, request.dryRun)

        return try {
            val result = adminSummaryService.sendSummaryEmail(request.dryRun, request.verbose)
            HttpResponse.ok(AdminSummaryResultDto(
                status = result.status.name,
                recipientCount = result.recipientCount,
                emailsSent = result.emailsSent,
                emailsFailed = result.emailsFailed,
                recipients = result.recipients,
                failedRecipients = result.failedRecipients
            ))
        } catch (e: Exception) {
            logger.error("Error sending admin summary", e)
            HttpResponse.serverError()
        }
    }

    // --- User Mapping Statistics Email Endpoint (Feature 085) ---

    @Serdeable
    data class SendUserMappingStatisticsRequest(
        val filterEmail: String? = null,
        val filterStatus: String? = null,
        val dryRun: Boolean = false,
        val verbose: Boolean = false,
        val importSummary: ImportSummaryDto? = null
    )

    @Serdeable
    data class ImportSummaryDto(
        val source: String? = null,
        val totalProcessed: Int? = null,
        val created: Int? = null,
        val createdPending: Int? = null,
        val skipped: Int? = null,
        val errorCount: Int? = null,
        val dbMappingCount: Int? = null,
        val fileMappingCount: Int? = null,
        val newCount: Int? = null,
        val unchangedCount: Int? = null,
        val removedCount: Int? = null
    )

    @Serdeable
    data class AggregatesDto(
        val totalUsers: Int,
        val totalMappings: Int,
        val activeMappings: Int,
        val pendingMappings: Int,
        val domainMappings: Int,
        val awsAccountMappings: Int
    )

    @Serdeable
    data class UserMappingStatisticsResultDto(
        val status: String,
        val recipientCount: Int,
        val emailsSent: Int,
        val emailsFailed: Int,
        val recipients: List<String>,
        val failedRecipients: List<String>,
        val appliedFilters: Map<String, String>,
        val aggregates: AggregatesDto
    )

    /**
     * POST /api/cli/user-mappings/send-statistics-email
     *
     * Computes user-mapping statistics (aggregates + per-user detail) for the given
     * filters and emails the report to every ADMIN or REPORT user with a valid email
     * address. Writes one audit row to user_mapping_statistics_log per invocation.
     *
     * Feature: 085-cli-mappings-email
     */
    @Post("/user-mappings/send-statistics-email")
    @Produces(MediaType.APPLICATION_JSON)
    fun sendUserMappingStatistics(
        @Body request: SendUserMappingStatisticsRequest,
        authentication: Authentication
    ): HttpResponse<Any> {
        logger.info("CLI user-mappings send-statistics-email requested by user: {} (dryRun={}, filterEmail={}, filterStatus={})",
            authentication.name, request.dryRun, request.filterEmail, request.filterStatus)

        return try {
            val importSummary = request.importSummary?.let {
                UserMappingStatisticsService.ImportSummary(
                    source = it.source,
                    totalProcessed = it.totalProcessed,
                    created = it.created,
                    createdPending = it.createdPending,
                    skipped = it.skipped,
                    errorCount = it.errorCount,
                    dbMappingCount = it.dbMappingCount,
                    fileMappingCount = it.fileMappingCount,
                    newCount = it.newCount,
                    unchangedCount = it.unchangedCount,
                    removedCount = it.removedCount
                )
            }
            val result = userMappingStatisticsService.sendStatisticsEmail(
                filterEmail = request.filterEmail,
                filterStatus = request.filterStatus,
                dryRun = request.dryRun,
                verbose = request.verbose,
                invokedBy = authentication.name,
                importSummary = importSummary
            )
            HttpResponse.ok(
                UserMappingStatisticsResultDto(
                    status = result.status.name,
                    recipientCount = result.recipientCount,
                    emailsSent = result.emailsSent,
                    emailsFailed = result.emailsFailed,
                    recipients = result.recipients,
                    failedRecipients = result.failedRecipients,
                    appliedFilters = result.appliedFilters,
                    aggregates = AggregatesDto(
                        totalUsers = result.aggregates.totalUsers,
                        totalMappings = result.aggregates.totalMappings,
                        activeMappings = result.aggregates.activeMappings,
                        pendingMappings = result.aggregates.pendingMappings,
                        domainMappings = result.aggregates.domainMappings,
                        awsAccountMappings = result.aggregates.awsAccountMappings
                    )
                ) as Any
            )
        } catch (e: IllegalArgumentException) {
            logger.warn("Invalid filter for user-mapping statistics: {}", e.message)
            HttpResponse.badRequest(
                mapOf(
                    "error" to "Validation Error",
                    "message" to (e.message ?: "Invalid filter parameter")
                ) as Any
            )
        } catch (e: Exception) {
            logger.error("Error sending user-mapping statistics email", e)
            HttpResponse.serverError(
                mapOf(
                    "error" to "Internal Server Error",
                    "message" to "Failed to send user-mapping statistics email"
                ) as Any
            )
        }
    }

    // --- User Vulnerability Notification Endpoints ---

    @Serdeable
    data class SendUserVulnNotificationsRequest(
        val dryRun: Boolean = false,
        val verbose: Boolean = false,
        val thresholdDays: Int = 30,
        val notificationUser: String? = null
    )

    @Serdeable
    data class UserVulnNotificationResultDto(
        val status: String,
        val awsAccountsAffected: Int,
        val usersNotified: Int,
        val emailsSent: Int,
        val emailsFailed: Int,
        val recipients: List<String>,
        val failedRecipients: List<String>,
        val unmappedAccounts: List<String>,
        val thresholdDays: Int
    )

    /**
     * POST /api/cli/user-vulnerability-notifications/send
     *
     * Finds AWS accounts with overdue vulnerabilities, maps them to users via UserMapping,
     * and sends each user one consolidated notification email.
     */
    @Post("/user-vulnerability-notifications/send")
    @Produces(MediaType.APPLICATION_JSON)
    fun sendUserVulnerabilityNotifications(
        @Body request: SendUserVulnNotificationsRequest,
        authentication: Authentication
    ): HttpResponse<UserVulnNotificationResultDto> {
        logger.info("CLI user-vulnerability-notifications requested by user: {} (dryRun={}, thresholdDays={})",
            authentication.name, request.dryRun, request.thresholdDays)

        return try {
            val result = userVulnerabilityNotificationService.sendUserVulnerabilityNotifications(
                thresholdDays = request.thresholdDays,
                dryRun = request.dryRun,
                verbose = request.verbose,
                notificationUser = request.notificationUser
            )

            HttpResponse.ok(UserVulnNotificationResultDto(
                status = result.status.name,
                awsAccountsAffected = result.awsAccountsAffected,
                usersNotified = result.usersNotified,
                emailsSent = result.emailsSent,
                emailsFailed = result.emailsFailed,
                recipients = result.recipients,
                failedRecipients = result.failedRecipients,
                unmappedAccounts = result.unmappedAccounts,
                thresholdDays = result.thresholdDays
            ))
        } catch (e: Exception) {
            logger.error("Error sending user vulnerability notifications", e)
            HttpResponse.serverError()
        }
    }
}
