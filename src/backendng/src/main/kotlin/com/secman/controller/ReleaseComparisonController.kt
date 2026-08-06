package com.secman.controller

import com.secman.service.RequirementComparisonService
import io.micronaut.http.HttpResponse
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.micronaut.http.annotation.QueryValue
import io.micronaut.security.annotation.Secured
import io.micronaut.security.rules.SecurityRule
import jakarta.inject.Inject
import org.slf4j.LoggerFactory

@Controller("/api/releases/compare")
@Secured(SecurityRule.IS_AUTHENTICATED)
class ReleaseComparisonController(
    @Inject private val comparisonService: RequirementComparisonService
) {
    private val logger = LoggerFactory.getLogger(ReleaseComparisonController::class.java)

    /**
     * GET /api/releases/compare - Compare two releases
     *
     * @param fromReleaseId Baseline release ID
     * @param toReleaseId Comparison release ID
     * @return ComparisonResult with added, deleted, modified, and unchanged
     */
    @Get
    fun compareReleases(
        @QueryValue("fromReleaseId") fromReleaseId: Long,
        @QueryValue("toReleaseId") toReleaseId: Long
    ): HttpResponse<Any> {
        logger.info("Comparing releases: fromReleaseId=$fromReleaseId, toReleaseId=$toReleaseId")

        try {
            val result = comparisonService.compare(fromReleaseId, toReleaseId)
            return HttpResponse.ok(result)
        } catch (e: IllegalArgumentException) {
            logger.warn("Comparison validation failed: ${e.message}")
            return HttpResponse.badRequest(
                mapOf(
                    "error" to "Bad Request",
                    "message" to (e.message ?: "Invalid release IDs")
                )
            )
        } catch (e: NoSuchElementException) {
            logger.warn("Release not found: ${e.message}")
            return HttpResponse.notFound(
                mapOf(
                    "error" to "Not Found",
                    "message" to (e.message ?: "Release not found")
                )
            )
        }
    }
}
