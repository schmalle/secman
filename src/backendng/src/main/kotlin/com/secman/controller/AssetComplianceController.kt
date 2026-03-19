package com.secman.controller

import com.secman.service.AssetComplianceTrackingService
import io.micronaut.http.HttpResponse
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.micronaut.http.annotation.Post
import io.micronaut.http.annotation.QueryValue
import io.micronaut.security.annotation.Secured
import org.slf4j.LoggerFactory

/**
 * REST Controller for Asset Compliance History.
 * Provides overview, per-asset timeline, summary, and recalculation endpoints.
 * Access: ADMIN role only.
 *
 * Feature: ec2-vulnerability-tracking
 */
@Controller("/api/asset-compliance")
@Secured("ADMIN")
class AssetComplianceController(
    private val complianceTrackingService: AssetComplianceTrackingService
) {
    private val log = LoggerFactory.getLogger(AssetComplianceController::class.java)

    /**
     * GET /api/asset-compliance/overview
     * Paginated list of all assets with their latest compliance status.
     */
    @Get("/overview")
    fun getOverview(
        @QueryValue(defaultValue = "") searchTerm: String?,
        @QueryValue(defaultValue = "") statusFilter: String?,
        @QueryValue(defaultValue = "0") page: Int,
        @QueryValue(defaultValue = "20") size: Int
    ): HttpResponse<Map<String, Any>> {
        log.debug("GET /api/asset-compliance/overview - search={}, status={}, page={}, size={}", searchTerm, statusFilter, page, size)
        val result = complianceTrackingService.getOverview(searchTerm, statusFilter, page, size)
        return HttpResponse.ok(result)
    }

    /**
     * GET /api/asset-compliance/{assetId}/history
     * Compliance history timeline for a single asset.
     */
    @Get("/{assetId}/history")
    fun getAssetHistory(assetId: Long): HttpResponse<Any> {
        log.debug("GET /api/asset-compliance/{}/history", assetId)
        val history = complianceTrackingService.getAssetHistory(assetId)
        return HttpResponse.ok(mapOf("assetId" to assetId, "history" to history))
    }

    /**
     * GET /api/asset-compliance/summary
     * Aggregate counts for dashboard summary cards.
     */
    @Get("/summary")
    fun getSummary(): HttpResponse<Any> {
        log.debug("GET /api/asset-compliance/summary")
        val summary = complianceTrackingService.getSummary()
        return HttpResponse.ok(summary)
    }

    /**
     * POST /api/asset-compliance/recalculate
     * Seed initial compliance records for all assets that don't have history yet.
     */
    @Post("/recalculate")
    fun recalculate(): HttpResponse<Any> {
        log.info("POST /api/asset-compliance/recalculate - starting bulk recalculation")
        val count = complianceTrackingService.recalculateAllStatuses("MANUAL_RECALCULATION")
        return HttpResponse.ok(mapOf("message" to "Recalculation complete", "assetsProcessed" to count))
    }
}
