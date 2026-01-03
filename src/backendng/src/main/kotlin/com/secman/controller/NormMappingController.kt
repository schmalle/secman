package com.secman.controller

import com.secman.dto.*
import com.secman.service.NormMappingService
import io.micronaut.http.HttpResponse
import io.micronaut.http.annotation.*
import io.micronaut.scheduling.TaskExecutors
import io.micronaut.scheduling.annotation.ExecuteOn
import io.micronaut.security.annotation.Secured
import org.slf4j.LoggerFactory

/**
 * Norm Mapping Controller
 * Feature: 058-ai-norm-mapping
 *
 * Provides AI-powered norm mapping suggestions for requirements.
 * Uses Claude Opus 4.5 via OpenRouter to suggest ISO 27001 and IEC 62443 mappings.
 *
 * Access Control:
 * - ADMIN: Full access to all norm mapping operations
 * - REQ: Full access to all norm mapping operations
 * - SECCHAMPION: Full access to all norm mapping operations
 * - Other roles: Access denied (403 Forbidden)
 */
@Controller("/api/norm-mapping")
@Secured("ADMIN", "REQ", "SECCHAMPION")
@ExecuteOn(TaskExecutors.BLOCKING)
open class NormMappingController(
    private val normMappingService: NormMappingService
) {

    private val log = LoggerFactory.getLogger(NormMappingController::class.java)

    /**
     * Get AI suggestions for norm mappings
     *
     * POST /api/norm-mapping/suggest
     *
     * Analyzes requirements without existing norm mappings and returns AI-generated
     * suggestions for ISO 27001 and IEC 62443 control mappings.
     */
    @Post("/suggest")
    open fun suggestMappings(@Body request: NormMappingSuggestionRequest?): HttpResponse<*> {
        return try {
            log.info("Received norm mapping suggestion request")

            val response = normMappingService.suggestMappings(request)

            log.info("Generated {} suggestions for {} requirements",
                response.totalSuggestionsGenerated, response.totalRequirementsAnalyzed)

            HttpResponse.ok(response)
        } catch (e: IllegalStateException) {
            log.error("Configuration error during norm mapping: {}", e.message)
            HttpResponse.badRequest(NormMappingErrorResponse(
                error = e.message ?: "Configuration error",
                details = "Please ensure TranslationConfig has a valid OpenRouter API key"
            ))
        } catch (e: RuntimeException) {
            log.error("AI service error during norm mapping: {}", e.message)
            HttpResponse.serverError(NormMappingErrorResponse(
                error = "AI service error",
                details = e.message
            ))
        } catch (e: Exception) {
            log.error("Unexpected error during norm mapping", e)
            HttpResponse.serverError(NormMappingErrorResponse(
                error = "Unexpected error",
                details = e.message
            ))
        }
    }

    /**
     * Apply selected norm mappings
     *
     * POST /api/norm-mapping/apply
     *
     * Saves the selected AI-suggested norm mappings to requirements.
     * Creates new norm entries if they don't exist in the database.
     */
    @Post("/apply")
    open fun applyMappings(@Body request: ApplyMappingsRequest): HttpResponse<*> {
        return try {
            log.info("Received apply mappings request for {} requirements", request.mappings.size)

            if (request.mappings.isEmpty()) {
                return HttpResponse.badRequest(NormMappingErrorResponse(
                    error = "No mappings provided",
                    details = "Request must include at least one mapping to apply"
                ))
            }

            val response = normMappingService.applyMappings(request)

            log.info("Applied mappings: {} requirements updated, {} new norms created, {} existing norms linked",
                response.updatedRequirements, response.newNormsCreated, response.existingNormsLinked)

            HttpResponse.ok(response)
        } catch (e: Exception) {
            log.error("Error applying norm mappings", e)
            HttpResponse.serverError(NormMappingErrorResponse(
                error = "Failed to apply mappings",
                details = e.message
            ))
        }
    }

    /**
     * Get count of unmapped requirements
     *
     * GET /api/norm-mapping/unmapped-count
     *
     * Returns the number of requirements without any norm mappings.
     */
    @Get("/unmapped-count")
    open fun getUnmappedCount(): HttpResponse<*> {
        return try {
            val count = normMappingService.getUnmappedCount()
            log.debug("Unmapped requirements count: {}", count)
            HttpResponse.ok(UnmappedCountResponse(count))
        } catch (e: Exception) {
            log.error("Error getting unmapped count", e)
            HttpResponse.serverError(NormMappingErrorResponse(
                error = "Failed to get unmapped count",
                details = e.message
            ))
        }
    }
}
