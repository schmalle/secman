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
     * Get current user's dashboard card preferences
     * Returns default values (all cards visible) if no preferences exist
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

        // Apply the request onto either the existing row or a fresh one, so the card list
        // is enumerated once rather than duplicated across an update and an insert branch.
        val merged = existing.orElseGet { DashboardPreference(userId = userId) }.copy(
            showAwsCleanServerKpi = request.showAwsCleanServerKpi,
            showEdrCoverageKpi = request.showEdrCoverageKpi,
            showAccountFindingAge = request.showAccountFindingAge,
            showAssetInventory = request.showAssetInventory,
            showUsers = request.showUsers,
            showActiveUsers = request.showActiveUsers,
            showActiveReleases = request.showActiveReleases,
            showRunningRiskAssessments = request.showRunningRiskAssessments,
            showLastCrowdStrikeImport = request.showLastCrowdStrikeImport,
            updatedAt = Instant.now()
        )

        val preference = if (existing.isPresent) {
            dashboardPreferenceRepository.update(merged)
            merged
        } else {
            dashboardPreferenceRepository.save(merged)
        }

        logger.info("Updated dashboard card preferences for user $userId: $request")

        return DashboardPreferenceResponse.from(preference)
    }

    @Serdeable
    data class UpdatePreferenceRequest(
        val showAwsCleanServerKpi: Boolean,
        val showEdrCoverageKpi: Boolean,
        val showAccountFindingAge: Boolean,
        val showAssetInventory: Boolean,
        val showUsers: Boolean,
        val showActiveUsers: Boolean,
        val showActiveReleases: Boolean,
        val showRunningRiskAssessments: Boolean,
        val showLastCrowdStrikeImport: Boolean
    )

    @Serdeable
    data class DashboardPreferenceResponse(
        val id: Long?,
        val userId: Long,
        val showAwsCleanServerKpi: Boolean,
        val showEdrCoverageKpi: Boolean,
        val showAccountFindingAge: Boolean,
        val showAssetInventory: Boolean,
        val showUsers: Boolean,
        val showActiveUsers: Boolean,
        val showActiveReleases: Boolean,
        val showRunningRiskAssessments: Boolean,
        val showLastCrowdStrikeImport: Boolean,
        val createdAt: Instant,
        val updatedAt: Instant
    ) {
        companion object {
            fun from(preference: DashboardPreference) = DashboardPreferenceResponse(
                id = preference.id,
                userId = preference.userId,
                showAwsCleanServerKpi = preference.showAwsCleanServerKpi,
                showEdrCoverageKpi = preference.showEdrCoverageKpi,
                showAccountFindingAge = preference.showAccountFindingAge,
                showAssetInventory = preference.showAssetInventory,
                showUsers = preference.showUsers,
                showActiveUsers = preference.showActiveUsers,
                showActiveReleases = preference.showActiveReleases,
                showRunningRiskAssessments = preference.showRunningRiskAssessments,
                showLastCrowdStrikeImport = preference.showLastCrowdStrikeImport,
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
