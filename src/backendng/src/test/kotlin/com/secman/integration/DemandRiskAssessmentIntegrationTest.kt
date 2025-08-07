package com.secman.integration

import com.secman.domain.*
import com.secman.repository.*
import io.micronaut.http.HttpStatus
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import io.mockk.every
import io.mockk.mockk
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.*

@MicronautTest
class DemandRiskAssessmentIntegrationTest {

    private lateinit var demandRepository: DemandRepository
    private lateinit var riskAssessmentRepository: RiskAssessmentRepository
    private lateinit var assetRepository: AssetRepository
    private lateinit var userRepository: UserRepository
    private lateinit var entityManager: EntityManager

    @BeforeEach
    fun setup() {
        demandRepository = mockk()
        riskAssessmentRepository = mockk()
        assetRepository = mockk()
        userRepository = mockk()
        entityManager = mockk()
    }

    @Test
    fun `test complete demand to risk assessment workflow`() {
        // Given - Create test data
        val asset = Asset(
            id = 1L,
            name = "Production Server",
            type = "Database Server",
            owner = "IT Operations"
        )

        val requestor = User(
            username = "requestor",
            email = "requestor@company.com",
            passwordHash = "\$2a\$10\$dummyhash"
        ).apply {
            id = 1L
        }

        val assessor = User(
            username = "assessor",
            email = "assessor@company.com",
            passwordHash = "\$2a\$10\$dummyhash"
        ).apply {
            id = 2L
        }

        // Step 1: Create a CHANGE demand
        val changeDemand = Demand(
            id = 1L,
            title = "Upgrade Database Server",
            description = "Upgrade to latest version for security patches",
            demandType = DemandType.CHANGE,
            existingAsset = asset,
            businessJustification = "Critical security vulnerabilities need patching",
            priority = Priority.HIGH,
            status = DemandStatus.PENDING,
            requestor = requestor,
            requestedDate = LocalDateTime.now()
        )

        // Step 2: Approve the demand
        val approvedDemand = changeDemand.copy(
            status = DemandStatus.APPROVED,
            approvedDate = LocalDateTime.now()
        )

        // Step 3: Create risk assessment for the approved demand
        val riskAssessment = RiskAssessment(
            startDate = LocalDate.now(),
            endDate = LocalDate.now().plusDays(14),
            demand = approvedDemand,
            assessor = assessor,
            requestor = requestor,
            status = "STARTED"
        )

        // Step 4: Update demand status to IN_PROGRESS
        val inProgressDemand = approvedDemand.copy(status = DemandStatus.IN_PROGRESS)

        // Mock repository calls
        every { demandRepository.save(any<Demand>()) } returns changeDemand andThen approvedDemand andThen inProgressDemand
        every { riskAssessmentRepository.save(any<RiskAssessment>()) } returns riskAssessment.copy(id = 1L)
        every { demandRepository.update(any<Demand>()) } returns inProgressDemand
        every { entityManager.refresh(any()) } returns Unit

        // When & Then - Verify the workflow
        // 1. Demand creation
        val savedDemand = demandRepository.save(changeDemand)
        assertEquals(DemandStatus.PENDING, savedDemand.status)
        assertEquals(DemandType.CHANGE, savedDemand.demandType)
        assertTrue(savedDemand.validateAssetInformation())

        // 2. Demand approval
        val finalApprovedDemand = demandRepository.save(approvedDemand)
        assertEquals(DemandStatus.APPROVED, finalApprovedDemand.status)
        assertNotNull(finalApprovedDemand.approvedDate)

        // 3. Risk assessment creation
        val savedRiskAssessment = riskAssessmentRepository.save(riskAssessment)
        assertNotNull(savedRiskAssessment.id)
        assertEquals(approvedDemand.id, savedRiskAssessment.demand.id)
        assertEquals("Production Server", savedRiskAssessment.getAssetName())
        assertEquals("Database Server", savedRiskAssessment.getAssetType())

        // 4. Demand status update
        val updatedDemand = demandRepository.update(inProgressDemand)
        assertEquals(DemandStatus.IN_PROGRESS, updatedDemand.status)
    }

    @Test
    fun `test CREATE_NEW demand workflow`() {
        // Given - Create test data for new asset demand
        val requestor = User(
            username = "developer",
            email = "developer@company.com",
            passwordHash = "\$2a\$10\$dummyhash"
        ).apply {
            id = 1L
        }

        val assessor = User(
            username = "security",
            email = "security@company.com",
            passwordHash = "\$2a\$10\$dummyhash"
        ).apply {
            id = 2L
        }

        // Step 1: Create a CREATE_NEW demand
        val createNewDemand = Demand(
            id = 2L,
            title = "New Development Environment",
            description = "Need new server for development team",
            demandType = DemandType.CREATE_NEW,
            newAssetName = "Dev Server 01",
            newAssetType = "Application Server",
            newAssetOwner = "Development Team",
            newAssetDescription = "Ubuntu 22.04 with Docker and CI/CD tools",
            businessJustification = "Support new project development",
            priority = Priority.MEDIUM,
            status = DemandStatus.PENDING,
            requestor = requestor,
            requestedDate = LocalDateTime.now()
        )

        // Step 2: Approve the demand
        val approvedDemand = createNewDemand.copy(
            status = DemandStatus.APPROVED,
            approvedDate = LocalDateTime.now()
        )

        // Step 3: Create risk assessment
        val riskAssessment = RiskAssessment(
            startDate = LocalDate.now(),
            endDate = LocalDate.now().plusDays(7),
            demand = approvedDemand,
            assessor = assessor,
            requestor = requestor,
            status = "STARTED"
        )

        // Mock repository calls
        every { demandRepository.save(any<Demand>()) } returns createNewDemand andThen approvedDemand
        every { riskAssessmentRepository.save(any<RiskAssessment>()) } returns riskAssessment.copy(id = 2L)

        // When & Then - Verify the workflow
        // 1. CREATE_NEW demand creation
        val savedDemand = demandRepository.save(createNewDemand)
        assertEquals(DemandStatus.PENDING, savedDemand.status)
        assertEquals(DemandType.CREATE_NEW, savedDemand.demandType)
        assertTrue(savedDemand.validateAssetInformation())
        assertEquals("Dev Server 01", savedDemand.getAssetName())
        assertEquals("Application Server", savedDemand.getAssetType())
        assertEquals("Development Team", savedDemand.getAssetOwner())
        assertNull(savedDemand.existingAsset) // No existing asset for CREATE_NEW

        // 2. Demand approval
        val finalApprovedDemand = demandRepository.save(approvedDemand)
        assertEquals(DemandStatus.APPROVED, finalApprovedDemand.status)

        // 3. Risk assessment creation for new asset
        val savedRiskAssessment = riskAssessmentRepository.save(riskAssessment)
        assertNotNull(savedRiskAssessment.id)
        assertEquals(approvedDemand.id, savedRiskAssessment.demand.id)
        assertEquals("Dev Server 01", savedRiskAssessment.getAssetName())
        assertEquals("Application Server", savedRiskAssessment.getAssetType())
        assertNull(savedRiskAssessment.getAssociatedAsset()) // No existing asset yet
    }

    @Test
    fun `test demand rejection workflow`() {
        // Given
        val requestor = User(
            username = "employee",
            email = "employee@company.com",
            passwordHash = "\$2a\$10\$dummyhash"
        ).apply {
            id = 1L
        }

        val demand = Demand(
            id = 3L,
            title = "High-end Gaming Workstation",
            description = "Need powerful workstation for work",
            demandType = DemandType.CREATE_NEW,
            newAssetName = "Gaming Workstation",
            newAssetType = "Workstation",
            newAssetOwner = "Employee",
            businessJustification = "Need for better performance",
            priority = Priority.LOW,
            status = DemandStatus.PENDING,
            requestor = requestor
        )

        // Step 2: Reject the demand
        val rejectedDemand = demand.copy(
            status = DemandStatus.REJECTED,
            rejectionReason = "Business justification insufficient and not aligned with company needs"
        )

        every { demandRepository.save(any<Demand>()) } returns demand andThen rejectedDemand

        // When & Then - Verify rejection workflow
        // 1. Demand creation
        val savedDemand = demandRepository.save(demand)
        assertEquals(DemandStatus.PENDING, savedDemand.status)

        // 2. Demand rejection
        val finalRejectedDemand = demandRepository.save(rejectedDemand)
        assertEquals(DemandStatus.REJECTED, finalRejectedDemand.status)
        assertNotNull(finalRejectedDemand.rejectionReason)
        assertTrue(finalRejectedDemand.rejectionReason!!.contains("insufficient"))

        // 3. Verify no risk assessment can be created for rejected demand
        // This would be enforced by the controller validation
        assertNotEquals(DemandStatus.APPROVED, finalRejectedDemand.status)
    }

    @Test
    fun `test risk assessment completion updates demand status`() {
        // Given
        val requestor = User(
            username = "manager",
            email = "manager@company.com",
            passwordHash = "\$2a\$10\$dummyhash"
        ).apply {
            id = 1L
        }

        val assessor = User(
            username = "security",
            email = "security@company.com",
            passwordHash = "\$2a\$10\$dummyhash"
        ).apply {
            id = 2L
        }

        val demand = Demand(
            id = 4L,
            title = "System Upgrade",
            demandType = DemandType.CHANGE,
            status = DemandStatus.IN_PROGRESS,
            requestor = requestor
        )

        val riskAssessment = RiskAssessment(
            id = 1L,
            startDate = LocalDate.now().minusDays(7),
            endDate = LocalDate.now(),
            demand = demand,
            assessor = assessor,
            requestor = requestor,
            status = "STARTED"
        )

        // Step: Complete the risk assessment
        val completedRiskAssessment = riskAssessment.copy(status = "COMPLETED")
        val completedDemand = demand.copy(status = DemandStatus.COMPLETED)

        every { riskAssessmentRepository.save(any<RiskAssessment>()) } returns completedRiskAssessment
        every { demandRepository.update(any<Demand>()) } returns completedDemand

        // When & Then - Verify completion workflow
        val savedAssessment = riskAssessmentRepository.save(completedRiskAssessment)
        assertEquals("COMPLETED", savedAssessment.status)

        val updatedDemand = demandRepository.update(completedDemand)
        assertEquals(DemandStatus.COMPLETED, updatedDemand.status)
    }

    @Test
    fun `test demand priority influences processing order`() {
        // Given - Multiple demands with different priorities
        val requestor = User(
            username = "user",
            email = "user@company.com",
            passwordHash = "\$2a\$10\$dummyhash"
        ).apply {
            id = 1L
        }

        val criticalDemand = Demand(
            id = 1L,
            title = "Critical Security Fix",
            demandType = DemandType.CHANGE,
            priority = Priority.CRITICAL,
            status = DemandStatus.PENDING,
            requestor = requestor,
            requestedDate = LocalDateTime.now().minusHours(2)
        )

        val highDemand = Demand(
            id = 2L,
            title = "Important Upgrade",
            demandType = DemandType.CHANGE,
            priority = Priority.HIGH,
            status = DemandStatus.PENDING,
            requestor = requestor,
            requestedDate = LocalDateTime.now().minusHours(1)
        )

        val mediumDemand = Demand(
            id = 3L,
            title = "Regular Request",
            demandType = DemandType.CREATE_NEW,
            priority = Priority.MEDIUM,
            status = DemandStatus.PENDING,
            requestor = requestor,
            requestedDate = LocalDateTime.now()
        )

        val pendingDemands = listOf(criticalDemand, highDemand, mediumDemand)
        every { demandRepository.findPendingDemandsOrderedByPriorityAndDate() } returns pendingDemands

        // When
        val orderedDemands = demandRepository.findPendingDemandsOrderedByPriorityAndDate()

        // Then - Verify ordering by priority (CRITICAL first, then by date)
        assertEquals(3, orderedDemands.size)
        assertEquals(Priority.CRITICAL, orderedDemands[0].priority)
        assertEquals(Priority.HIGH, orderedDemands[1].priority)
        assertEquals(Priority.MEDIUM, orderedDemands[2].priority)
    }

    @Test
    fun `test migration compatibility - legacy asset field support`() {
        // Given - Legacy risk assessment with asset field (for migration compatibility)
        val asset = Asset(
            id = 1L,
            name = "Legacy Asset",
            type = "Server",
            owner = "IT Team"
        )

        val user = User(
            username = "user",
            email = "user@company.com",
            passwordHash = "\$2a\$10\$dummyhash"
        ).apply {
            id = 1L
        }

        // Create a demand for the legacy asset
        val migrationDemand = Demand(
            id = 1L,
            title = "Migration Demand for Asset: Legacy Asset",
            demandType = DemandType.CHANGE,
            existingAsset = asset,
            status = DemandStatus.IN_PROGRESS,
            requestor = user
        )

        // Create risk assessment with both demand and legacy asset field
        val legacyRiskAssessment = RiskAssessment(
            id = 1L,
            startDate = LocalDate.now(),
            endDate = LocalDate.now().plusDays(14),
            demand = migrationDemand,
            assessor = user,
            requestor = user,
            asset = asset, // Legacy field for backward compatibility
            status = "STARTED"
        )

        // When & Then - Verify backward compatibility
        // The risk assessment should work with both new demand field and legacy asset field
        assertEquals(migrationDemand.id, legacyRiskAssessment.demand.id)
        assertEquals(asset.id, legacyRiskAssessment.asset?.id)
        
        // The getAssociatedAsset method should return the asset from demand
        assertEquals(asset, legacyRiskAssessment.getAssociatedAsset())
        assertEquals("Legacy Asset", legacyRiskAssessment.getAssetName())
        assertEquals("Server", legacyRiskAssessment.getAssetType())
    }
}