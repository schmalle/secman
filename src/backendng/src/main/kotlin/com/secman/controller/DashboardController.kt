package com.secman.controller

import com.secman.dto.AwsCleanServerKpiResponse
import com.secman.service.AwsCleanServerKpiService
import io.micronaut.http.HttpResponse
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.micronaut.security.annotation.Secured

/**
 * Endpoints backing dashboard widgets that show pre-computed, role-gated
 * security KPIs.
 */
@Controller("/api/dashboard")
class DashboardController(
    private val awsCleanServerKpiService: AwsCleanServerKpiService
) {
    /**
     * GET /api/dashboard/aws-clean-server-kpi
     *
     * Percentage of AWS servers with no vulnerability older than 30 days.
     * Always reads a pre-computed cache row (recalculated after every
     * CrowdStrike import) so the response is fast regardless of fleet size.
     * `available: false` means no calculation has completed yet.
     *
     * Access: ADMIN, SECCHAMPION
     */
    @Get("/aws-clean-server-kpi")
    @Secured("ADMIN", "SECCHAMPION")
    fun getAwsCleanServerKpi(): HttpResponse<AwsCleanServerKpiResponse> {
        return HttpResponse.ok(awsCleanServerKpiService.getKpi())
    }
}
