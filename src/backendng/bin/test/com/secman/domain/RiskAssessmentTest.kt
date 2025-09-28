package com.secman.domain

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.LocalDate

class RiskAssessmentTest {

    private fun createTestUser(id: Long, username: String): User {
        return User(
            username = username,
            email = "$username@test.com",
            passwordHash = "password"
        ).apply { this.id = id }
    }

    private fun createTestDemand(id: Long, title: String): Demand {
        return Demand(
            title = title,
            demandType = DemandType.CREATE_NEW,
            requestor = createTestUser(1L, "requestor"),
            newAssetName = "Test Asset",
            newAssetType = "Server",
            newAssetOwner = "Test Owner"
        ).apply { this.id = id }
    }

    private fun createTestAsset(id: Long, name: String): Asset {
        return Asset(
            name = name,
            type = "Server",
            owner = "Test Owner"
        ).apply { this.id = id }
    }

    @Test
    fun `demand-based risk assessment should set correct basis type and id`() {
        val demand = createTestDemand(100L, "Test Demand")
        val assessor = createTestUser(2L, "assessor")
        val requestor = createTestUser(3L, "requestor")

        val riskAssessment = RiskAssessment(
            startDate = LocalDate.now(),
            endDate = LocalDate.now().plusDays(30),
            demand = demand,
            assessor = assessor,
            requestor = requestor
        )

        assertEquals(AssessmentBasisType.DEMAND, riskAssessment.assessmentBasisType)
        assertEquals(100L, riskAssessment.assessmentBasisId)
        assertEquals(demand, riskAssessment.demand)
        assertNull(riskAssessment.asset)
    }

    @Test
    fun `asset-based risk assessment should set correct basis type and id`() {
        val asset = createTestAsset(200L, "Test Asset")
        val assessor = createTestUser(2L, "assessor")
        val requestor = createTestUser(3L, "requestor")

        val riskAssessment = RiskAssessment(
            startDate = LocalDate.now(),
            endDate = LocalDate.now().plusDays(30),
            asset = asset,
            assessor = assessor,
            requestor = requestor
        )

        assertEquals(AssessmentBasisType.ASSET, riskAssessment.assessmentBasisType)
        assertEquals(200L, riskAssessment.assessmentBasisId)
        assertEquals(asset, riskAssessment.asset)
        assertNull(riskAssessment.demand)
    }

    @Test
    fun `getDemandBasis should return demand for DEMAND basis type`() {
        val demand = createTestDemand(100L, "Test Demand")
        val assessor = createTestUser(2L, "assessor")
        val requestor = createTestUser(3L, "requestor")

        val riskAssessment = RiskAssessment(
            startDate = LocalDate.now(),
            endDate = LocalDate.now().plusDays(30),
            demand = demand,
            assessor = assessor,
            requestor = requestor
        )

        assertEquals(demand, riskAssessment.getDemandBasis())
        assertNull(riskAssessment.getAssetBasis())
    }

    @Test
    fun `getAssetBasis should return asset for ASSET basis type`() {
        val asset = createTestAsset(200L, "Test Asset")
        val assessor = createTestUser(2L, "assessor")
        val requestor = createTestUser(3L, "requestor")

        val riskAssessment = RiskAssessment(
            startDate = LocalDate.now(),
            endDate = LocalDate.now().plusDays(30),
            asset = asset,
            assessor = assessor,
            requestor = requestor
        )

        assertEquals(asset, riskAssessment.getAssetBasis())
        assertNull(riskAssessment.getDemandBasis())
    }

    @Test
    fun `getBasisDescription should return correct description for both types`() {
        val demand = createTestDemand(100L, "Test Demand")
        val asset = createTestAsset(200L, "Test Asset")
        val assessor = createTestUser(2L, "assessor")
        val requestor = createTestUser(3L, "requestor")

        val demandAssessment = RiskAssessment(
            startDate = LocalDate.now(),
            endDate = LocalDate.now().plusDays(30),
            demand = demand,
            assessor = assessor,
            requestor = requestor
        )

        val assetAssessment = RiskAssessment(
            startDate = LocalDate.now(),
            endDate = LocalDate.now().plusDays(30),
            asset = asset,
            assessor = assessor,
            requestor = requestor
        )

        assertEquals("Test Demand", demandAssessment.getBasisDescription())
        assertEquals("Test Asset", assetAssessment.getBasisDescription())
    }

    @Test
    fun `validateBasisConsistency should return true for consistent configuration`() {
        val demand = createTestDemand(100L, "Test Demand")
        val assessor = createTestUser(2L, "assessor")
        val requestor = createTestUser(3L, "requestor")

        val riskAssessment = RiskAssessment(
            startDate = LocalDate.now(),
            endDate = LocalDate.now().plusDays(30),
            demand = demand,
            assessor = assessor,
            requestor = requestor
        )

        assertTrue(riskAssessment.validateBasisConsistency())
        assertTrue(riskAssessment.getBasisValidationErrors().isEmpty())
    }

    @Test
    fun `toString should include basis type and description`() {
        val demand = createTestDemand(100L, "Test Demand")
        val assessor = createTestUser(2L, "assessor")
        val requestor = createTestUser(3L, "requestor")

        val riskAssessment = RiskAssessment(
            startDate = LocalDate.now(),
            endDate = LocalDate.now().plusDays(30),
            demand = demand,
            assessor = assessor,
            requestor = requestor
        )

        val toStringResult = riskAssessment.toString()
        assertTrue(toStringResult.contains("basisType=DEMAND"))
        assertTrue(toStringResult.contains("basis=Test Demand"))
    }
}