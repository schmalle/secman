package com.secman.controller

import com.secman.dto.AwsCleanServerKpiResponse
import com.secman.dto.EdrCoverageKpiResponse
import com.secman.service.AwsCleanServerKpiService
import com.secman.service.EdrCoverageKpiService
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
    private val awsCleanServerKpiService: AwsCleanServerKpiService,
    private val edrCoverageKpiService: EdrCoverageKpiService
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

    /**
     * GET /api/dashboard/edr-coverage-kpi
     *
     * Percentage of EC2 instances running a CrowdStrike sensor. Instances with an approved
     * "No EDR possible" exception are removed from the denominator and reported separately.
     * Like the KPI above this only ever reads a pre-computed cache row, never a live query.
     * `available: false` means the value would not yet be a measurement — see
     * EdrCoverageKpiService.getKpi.
     *
     * Access: ADMIN, SECCHAMPION
     */
    @Get("/edr-coverage-kpi")
    @Secured("ADMIN", "SECCHAMPION")
    fun getEdrCoverageKpi(): HttpResponse<EdrCoverageKpiResponse> {
        return HttpResponse.ok(edrCoverageKpiService.getKpi())
    }
}
