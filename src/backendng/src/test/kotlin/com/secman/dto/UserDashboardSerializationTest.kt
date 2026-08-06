package com.secman.dto

import io.micronaut.serde.ObjectMapper
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Guards the wire contract of GET /api/user-dashboard: Micronaut Serde's
 * default inclusion is NON_EMPTY, which silently drops empty collections.
 * The frontend (UserTodoDashboard.tsx) requires riskAssessments to always
 * be present — a missing key crashed the dashboard for regular users with
 * no open risk assessments.
 */
class UserDashboardSerializationTest {

    private val mapper = ObjectMapper.getDefault()

    @Test
    fun `empty riskAssessments serializes as empty array instead of being omitted`() {
        val response = UserDashboardResponse(
            assetCount = 3,
            vulnerabilities = VulnerabilitySeverityCounts(1, 2, 3, 4, 0, 10),
            overdue = OverdueAssetSummary(1, 1, 0, 12, null),
            exceptionRequests = ExceptionRequestSummaryDto(0, 0, 0, 0, 0, 0),
            riskAssessments = emptyList(),
            views = DashboardViewFlags(accountVulns = true, workgroupVulns = false, domainVulns = false)
        )

        val json = mapper.writeValueAsString(response)

        assertTrue(
            json.contains("\"riskAssessments\":[]"),
            "riskAssessments must be serialized as [] when empty, got: $json"
        )
    }
}
