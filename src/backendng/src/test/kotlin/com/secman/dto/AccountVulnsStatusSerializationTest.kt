package com.secman.dto

import io.micronaut.serde.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Guards the wire contract of the intervention-status fields on GET /api/account-vulns.
 *
 * Micronaut Serde's default inclusion is NON_EMPTY (see UserDashboardSerializationTest), so it
 * is worth pinning that the status enum survives serialization and reaches the frontend as the
 * plain string the AssetInterventionStatus TypeScript union expects. AssetStatusLamp renders
 * nothing when status is absent, so a silently dropped field would make the lamp disappear
 * rather than fail loudly.
 */
class AccountVulnsStatusSerializationTest {

    private val mapper = ObjectMapper.getDefault()

    private fun asset(status: AssetInterventionStatus, overdue: Int) = AssetVulnCountDto(
        id = 1L,
        name = "host",
        type = "SERVER",
        vulnerabilityCount = 3,
        criticalCount = 0,
        highCount = 3,
        mediumCount = 0,
        exceptedCount = 1,
        nonExceptedCount = 2,
        nonExceptedOverdueCount = overdue,
        status = status
    )

    @Test
    fun `asset status serializes as the plain enum name`() {
        val json = mapper.writeValueAsString(asset(AssetInterventionStatus.RED, 1))

        assertThat(json).contains("\"status\":\"RED\"")
        assertThat(json).contains("\"nonExceptedOverdueCount\":1")
    }

    @Test
    fun `GREEN status is not dropped as an empty value`() {
        // GREEN is the most common value; if NON_EMPTY inclusion ever dropped it the lamp would
        // silently vanish for exactly the assets that are fine.
        val json = mapper.writeValueAsString(asset(AssetInterventionStatus.GREEN, 0))

        assertThat(json).contains("\"status\":\"GREEN\"")
    }

    @Test
    fun `summary carries the threshold and roll-up so the UI never hardcodes 30 days`() {
        val summary = AccountVulnsSummaryDto(
            accountGroups = listOf(
                AccountGroupDto(
                    awsAccountId = "082782524287",
                    assets = listOf(asset(AssetInterventionStatus.RED, 1)),
                    totalAssets = 1,
                    totalVulnerabilities = 3,
                    totalExcepted = 1,
                    totalNonExcepted = 2,
                    assetsNeedingAttention = 1,
                    status = AssetInterventionStatus.RED
                )
            ),
            totalAssets = 1,
            totalVulnerabilities = 3,
            globalStatus = AssetInterventionStatus.RED,
            assetsNeedingAttention = 1,
            thresholdDays = 30
        )

        val json = mapper.writeValueAsString(summary)

        assertThat(json).contains("\"thresholdDays\":30")
        assertThat(json).contains("\"globalStatus\":\"RED\"")
        assertThat(json).contains("\"assetsNeedingAttention\":1")
    }

    @Test
    fun `workgroup group carries the same status contract`() {
        val group = WorkgroupGroupDto(
            workgroupId = 7L,
            workgroupName = "platform",
            assets = listOf(asset(AssetInterventionStatus.YELLOW, 0)),
            totalAssets = 1,
            totalVulnerabilities = 3,
            totalExcepted = 1,
            totalNonExcepted = 2,
            assetsNeedingAttention = 1,
            status = AssetInterventionStatus.YELLOW
        )

        val json = mapper.writeValueAsString(group)

        assertThat(json).contains("\"status\":\"YELLOW\"")
        assertThat(json).contains("\"totalExcepted\":1")
    }
}
