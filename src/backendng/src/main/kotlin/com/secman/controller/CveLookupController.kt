package com.secman.controller

import com.secman.dto.CveLookupDto
import com.secman.service.CveLookupService
import io.micronaut.http.HttpResponse
import io.micronaut.http.MediaType
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.micronaut.http.annotation.Produces
import io.micronaut.security.annotation.Secured
import io.micronaut.security.rules.SecurityRule
import org.slf4j.LoggerFactory

/**
 * REST controller for CVE lookup
 *
 * Proxies NVD API requests with caching to provide CVE descriptions
 * for hover popovers in the frontend.
 *
 * Feature: 072-cve-link-lookup
 */
@Controller("/api/cve")
@Secured(SecurityRule.IS_AUTHENTICATED)
class CveLookupController(
    private val cveLookupService: CveLookupService
) {
    private val logger = LoggerFactory.getLogger(CveLookupController::class.java)

    /**
     * GET /api/cve/{cveId}
     *
     * Look up CVE details from NVD API (cached for 24h).
     *
     * @param cveId CVE identifier (e.g., "CVE-2023-12345")
     * @return CVE details or 404 if not found
     */
    @Get("/{cveId}")
    @Produces(MediaType.APPLICATION_JSON)
    fun lookupCve(cveId: String): HttpResponse<CveLookupDto> {
        return try {
            val result = cveLookupService.lookupCve(cveId)
            if (result != null) {
                HttpResponse.ok(result)
            } else {
                HttpResponse.notFound()
            }
        } catch (e: Exception) {
            logger.error("Error looking up CVE: {}", cveId, e)
            HttpResponse.serverError()
        }
    }
}
