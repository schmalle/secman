package com.secman.controller

import com.secman.domain.DashboardPreference
import com.secman.repository.DashboardPreferenceRepository
import io.micronaut.http.annotation.*
import io.micronaut.security.annotation.Secured
import io.micronaut.security.authentication.Authentication
import io.micronaut.security.rules.SecurityRule
import io.micronaut.serde.annotation.Serdeable
import jakarta.inject.Singleton
import jakarta.validation.Valid
import org.slf4j.LoggerFactory
import java.time.Instant

/**
 * Controller for managing per-user home dashboard KPI card visibility preferences
 */
@Singleton
@Controller("/api/dashboard-preferences")
@Secured(SecurityRule.IS_AUTHENTICATED)
open class DashboardPreferenceController(
    private val dashboardPreferenceRepository: DashboardPreferenceRepository
) {
    private val logger = LoggerFactory.getLogger(DashboardPreferenceController::class.java)

    /**
     * Get current user's dashboard KPI preferences
     * Returns default values (both KPIs visible) if no preferences exist
     */
    @Get
    fun getUserPreferences(authentication: Authentication): DashboardPreferenceResponse {
        val userId = getUserIdFromAuthentication(authentication)

        val preference = dashboardPreferenceRepository.findByUserId(userId)
            .orElseGet {
                // Return defaults if not found
                DashboardPreference(userId = userId)
            }

        return DashboardPreferenceResponse.from(preference)
    }

    /**
     * Update current user's dashboard KPI preferences
     */
    @Put
    open fun updateUserPreferences(
        @Valid @Body request: UpdatePreferenceRequest,
        authentication: Authentication
    ): DashboardPreferenceResponse {
        val userId = getUserIdFromAuthentication(authentication)

        val existing = dashboardPreferenceRepository.findByUserId(userId)

        val preference = if (existing.isPresent) {
            // Update existing
            val updated = existing.get().copy(
                showAwsCleanServerKpi = request.showAwsCleanServerKpi,
                showEdrCoverageKpi = request.showEdrCoverageKpi,
                updatedAt = Instant.now()
            )
            dashboardPreferenceRepository.update(updated)
            updated
        } else {
            // Create new
            val newPref = DashboardPreference(
                userId = userId,
                showAwsCleanServerKpi = request.showAwsCleanServerKpi,
                showEdrCoverageKpi = request.showEdrCoverageKpi
            )
            dashboardPreferenceRepository.save(newPref)
        }

        logger.info("Updated dashboard preferences for user $userId: showAwsCleanServerKpi=${request.showAwsCleanServerKpi}, showEdrCoverageKpi=${request.showEdrCoverageKpi}")

        return DashboardPreferenceResponse.from(preference)
    }

    @Serdeable
    data class UpdatePreferenceRequest(
        val showAwsCleanServerKpi: Boolean,
        val showEdrCoverageKpi: Boolean
    )

    @Serdeable
    data class DashboardPreferenceResponse(
        val id: Long?,
        val userId: Long,
        val showAwsCleanServerKpi: Boolean,
        val showEdrCoverageKpi: Boolean,
        val createdAt: Instant,
        val updatedAt: Instant
    ) {
        companion object {
            fun from(preference: DashboardPreference) = DashboardPreferenceResponse(
                id = preference.id,
                userId = preference.userId,
                showAwsCleanServerKpi = preference.showAwsCleanServerKpi,
                showEdrCoverageKpi = preference.showEdrCoverageKpi,
                createdAt = preference.createdAt,
                updatedAt = preference.updatedAt
            )
        }
    }

    /**
     * Extract user ID from authentication object
     */
    private fun getUserIdFromAuthentication(authentication: Authentication): Long {
        val userId = authentication.attributes["userId"]
        return when (userId) {
            is Long -> userId
            is Int -> userId.toLong()
            is String -> userId.toLong()
            else -> throw IllegalStateException("Unable to determine user ID from authentication")
        }
    }
}
