package com.secman.controller

import com.secman.domain.AssetHeatmapEntry
import com.secman.dto.AssetHeatmapEntryDto
import com.secman.dto.AssetHeatmapResponseDto
import com.secman.dto.AssetHeatmapSummaryDto
import com.secman.repository.AssetHeatmapRepository
import com.secman.service.AssetHeatmapService
import com.secman.service.McpAuthenticationService
import com.secman.service.McpDelegationService
import com.secman.service.mcp.McpAccessControlService
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpResponse
import io.micronaut.http.MediaType
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.micronaut.http.annotation.Produces
import io.micronaut.security.annotation.Secured
import io.micronaut.security.rules.SecurityRule
import org.slf4j.LoggerFactory

/**
 * CORS-enabled REST endpoint for external web applications to consume heatmap data.
 *
 * Authenticates via MCP API key (X-MCP-API-Key) and user delegation (X-MCP-User-Email)
 * headers. Returns the same heatmap data as the internal endpoint, scoped by the
 * delegated user's access control.
 *
 * Feature: 086-heatmap-mcp-api
 */
@Controller("/api/external/vulnerability-heatmap")
@Secured(SecurityRule.IS_ANONYMOUS)
class ExternalHeatmapController(
    private val authService: McpAuthenticationService,
    private val delegationService: McpDelegationService,
    private val accessControlService: McpAccessControlService,
    private val assetHeatmapRepository: AssetHeatmapRepository,
    private val assetHeatmapService: AssetHeatmapService
) {
    private val logger = LoggerFactory.getLogger(ExternalHeatmapController::class.java)

    @Get
    @Produces(MediaType.APPLICATION_JSON)
    fun getHeatmap(request: HttpRequest<*>): HttpResponse<*> {
        // Step 1: Extract and validate API key
        val apiKeyHeader = request.headers.get("X-MCP-API-Key")
        if (apiKeyHeader.isNullOrBlank()) {
            return HttpResponse.unauthorized<Map<String, String>>()
                .body(mapOf("error" to "Missing X-MCP-API-Key header"))
        }

        val authResult = authService.authenticateApiKey(apiKeyHeader)
        if (!authResult.success || authResult.apiKey == null) {
            logger.warn("External heatmap: API key authentication failed: {}", authResult.errorMessage)
            val errorMsg: String = authResult.errorMessage ?: "Invalid API key"
            return HttpResponse.unauthorized<Map<String, String>>()
                .body(mapOf("error" to errorMsg))
        }

        // Step 2: Extract and validate user delegation
        val userEmail = request.headers.get("X-MCP-User-Email")
        if (userEmail.isNullOrBlank()) {
            return HttpResponse.unauthorized<Map<String, String>>()
                .body(mapOf("error" to "Missing X-MCP-User-Email header"))
        }

        val apiKey = authResult.apiKey
        val delegationResult = delegationService.validateDelegation(apiKey, userEmail)
        if (!delegationResult.success || delegationResult.user == null) {
            logger.warn("External heatmap: Delegation validation failed for {}: {}", userEmail, delegationResult.errorMessage)
            val delegationError: String = delegationResult.errorMessage ?: "Delegation failed"
            return HttpResponse.status<Map<String, String>>(io.micronaut.http.HttpStatus.FORBIDDEN)
                .body(mapOf("error" to delegationError))
        }

        // Step 3: Build execution context with access control
        val delegatedUser = delegationResult.user
        val delegationContext = DelegationContext(
            delegatedUserEmail = userEmail,
            delegatedUserId = delegatedUser.id!!,
            effectivePermissions = delegationResult.effectivePermissions
        )
        val executionContext = accessControlService.buildExecutionContext(apiKey, delegationContext)

        // Step 4: Query heatmap with access control
        return try {
            val accessibleAssetIds = executionContext.getFilterableAssetIds()

            val entries = if (accessibleAssetIds == null) {
                assetHeatmapRepository.findAll()
            } else if (accessibleAssetIds.isEmpty()) {
                emptyList()
            } else {
                assetHeatmapRepository.findAll().filter { accessibleAssetIds.contains(it.assetId) }
            }

            val dtos = entries.map { it.toDto() }
            val summary = AssetHeatmapSummaryDto(
                totalAssets = dtos.size,
                redCount = dtos.count { it.heatLevel == AssetHeatmapEntry.HEAT_RED },
                yellowCount = dtos.count { it.heatLevel == AssetHeatmapEntry.HEAT_YELLOW },
                greenCount = dtos.count { it.heatLevel == AssetHeatmapEntry.HEAT_GREEN }
            )

            val response = AssetHeatmapResponseDto(
                entries = dtos,
                summary = summary,
                lastCalculatedAt = assetHeatmapService.getLastCalculatedAt()?.toString()
            )

            logger.info("External heatmap query: {} entries returned for user {}", entries.size, userEmail)
            HttpResponse.ok(response)
        } catch (e: Exception) {
            logger.error("External heatmap query failed", e)
            HttpResponse.serverError<Map<String, String>>()
                .body(mapOf("error" to "Internal server error"))
        }
    }

    private fun AssetHeatmapEntry.toDto() = AssetHeatmapEntryDto(
        assetId = assetId,
        assetName = assetName,
        assetType = assetType,
        criticalCount = criticalCount,
        highCount = highCount,
        mediumCount = mediumCount,
        lowCount = lowCount,
        totalCount = totalCount,
        heatLevel = heatLevel
    )
}
