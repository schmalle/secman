package com.secman.controller

import com.secman.domain.*
import com.secman.repository.*
import io.micronaut.http.HttpStatus
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.util.*

@MicronautTest
class DemandControllerTest {

    private lateinit var demandRepository: DemandRepository
    private lateinit var assetRepository: AssetRepository
    private lateinit var userRepository: UserRepository
    private lateinit var entityManager: EntityManager
    private lateinit var demandController: DemandController

    @BeforeEach
    fun setup() {
        demandRepository = mockk()
        assetRepository = mockk()
        userRepository = mockk()
        entityManager = mockk()
        demandController = DemandController(demandRepository, assetRepository, userRepository, entityManager)
    }

    @Test
    fun `test create CHANGE demand successfully`() {
        // Given
        val asset = Asset(
            id = 1L,
            name = "Test Asset",
            type = "Server",
            owner = "IT Team"
        )
        
        val user = User(
            username = "testuser",
            email = "test@example.com",
            passwordHash = "\$2a\$10\$dummyhash"
        ).apply {
            id = 1L
        }

        val request = DemandController.CreateDemandRequest(
            title = "Change Request",
            description = "Update server configuration",
            demandType = DemandType.CHANGE,
            existingAssetId = 1L,
            businessJustification = "Improve performance",
            priority = Priority.HIGH,
            requestorId = 1L
        )

        val savedDemand = Demand(
            id = 1L,
            title = request.title,
            description = request.description,
            demandType = request.demandType,
            existingAsset = asset,
            businessJustification = request.businessJustification,
            priority = request.priority!!,
            requestor = user
        )

        every { userRepository.findById(1L) } returns Optional.of(user)
        every { assetRepository.findById(1L) } returns Optional.of(asset)
        every { demandRepository.save(any<Demand>()) } returns savedDemand
        every { entityManager.refresh(savedDemand) } returns Unit

        // When
        val response = demandController.createDemand(request)

        // Then
        assertEquals(HttpStatus.CREATED, response.status)
        assertNotNull(response.body)
        verify { demandRepository.save(any<Demand>()) }
    }

    @Test
    fun `test create CREATE_NEW demand successfully`() {
        // Given
        val user = User(
            username = "testuser",
            email = "test@example.com",
            passwordHash = "\$2a\$10\$dummyhash"
        ).apply {
            id = 1L
        }

        val request = DemandController.CreateDemandRequest(
            title = "New Server Request",
            description = "Need new application server",
            demandType = DemandType.CREATE_NEW,
            newAssetName = "App Server 01",
            newAssetType = "Application Server",
            newAssetOwner = "Development Team",
            businessJustification = "Support new application",
            priority = Priority.MEDIUM,
            requestorId = 1L
        )

        val savedDemand = Demand(
            id = 1L,
            title = request.title,
            description = request.description,
            demandType = request.demandType,
            newAssetName = request.newAssetName,
            newAssetType = request.newAssetType,
            newAssetOwner = request.newAssetOwner,
            businessJustification = request.businessJustification,
            priority = request.priority!!,
            requestor = user
        )

        every { userRepository.findById(1L) } returns Optional.of(user)
        every { demandRepository.save(any<Demand>()) } returns savedDemand
        every { entityManager.refresh(savedDemand) } returns Unit

        // When
        val response = demandController.createDemand(request)

        // Then
        assertEquals(HttpStatus.CREATED, response.status)
        assertNotNull(response.body)
        verify { demandRepository.save(any<Demand>()) }
    }

    @Test
    fun `test create CHANGE demand fails without asset`() {
        // Given
        val user = User(
            username = "testuser",
            email = "test@example.com",
            passwordHash = "\$2a\$10\$dummyhash"
        ).apply {
            id = 1L
        }

        val request = DemandController.CreateDemandRequest(
            title = "Change Request",
            demandType = DemandType.CHANGE,
            existingAssetId = 999L, // Non-existent asset
            requestorId = 1L
        )

        every { userRepository.findById(1L) } returns Optional.of(user)
        every { assetRepository.findById(999L) } returns Optional.empty()

        // When
        val response = demandController.createDemand(request)

        // Then
        assertEquals(HttpStatus.BAD_REQUEST, response.status)
        verify(exactly = 0) { demandRepository.save(any<Demand>()) }
    }

    @Test
    fun `test create CREATE_NEW demand fails with incomplete info`() {
        // Given
        val user = User(
            username = "testuser",
            email = "test@example.com",
            passwordHash = "\$2a\$10\$dummyhash"
        ).apply {
            id = 1L
        }

        val request = DemandController.CreateDemandRequest(
            title = "New Server Request",
            demandType = DemandType.CREATE_NEW,
            newAssetName = "App Server 01",
            newAssetType = null, // Missing required field
            newAssetOwner = "Development Team",
            requestorId = 1L
        )

        every { userRepository.findById(1L) } returns Optional.of(user)

        // When
        val response = demandController.createDemand(request)

        // Then
        assertEquals(HttpStatus.BAD_REQUEST, response.status)
        verify(exactly = 0) { demandRepository.save(any<Demand>()) }
    }

    @Test
    fun `test approve demand successfully`() {
        // Given
        val user = User(
            username = "testuser",
            email = "test@example.com",
            passwordHash = "\$2a\$10\$dummyhash"
        ).apply {
            id = 1L
        }

        val demand = Demand(
            id = 1L,
            title = "Test Demand",
            demandType = DemandType.CREATE_NEW,
            newAssetName = "Test Asset",
            newAssetType = "Server",
            newAssetOwner = "IT Team",
            status = DemandStatus.PENDING,
            requestor = user
        )

        val request = DemandController.ApproveDemandRequest(approved = true)

        every { demandRepository.findById(1L) } returns Optional.of(demand)
        every { demandRepository.update(any<Demand>()) } returns demand.copy(status = DemandStatus.APPROVED)
        every { entityManager.refresh(any<Demand>()) } returns Unit

        // When
        val response = demandController.approveDemand(1L, request)

        // Then
        assertEquals(HttpStatus.OK, response.status)
        verify { demandRepository.update(any<Demand>()) }
    }

    @Test
    fun `test reject demand successfully`() {
        // Given
        val user = User(
            username = "testuser",
            email = "test@example.com",
            passwordHash = "\$2a\$10\$dummyhash"
        ).apply {
            id = 1L
        }

        val demand = Demand(
            id = 1L,
            title = "Test Demand",
            demandType = DemandType.CREATE_NEW,
            newAssetName = "Test Asset",
            newAssetType = "Server",
            newAssetOwner = "IT Team",
            status = DemandStatus.PENDING,
            requestor = user
        )

        val request = DemandController.ApproveDemandRequest(
            approved = false,
            rejectionReason = "Insufficient justification"
        )

        every { demandRepository.findById(1L) } returns Optional.of(demand)
        every { demandRepository.update(any<Demand>()) } returns demand.copy(
            status = DemandStatus.REJECTED,
            rejectionReason = "Insufficient justification"
        )
        every { entityManager.refresh(any<Demand>()) } returns Unit

        // When
        val response = demandController.approveDemand(1L, request)

        // Then
        assertEquals(HttpStatus.OK, response.status)
        verify { demandRepository.update(any<Demand>()) }
    }

    @Test
    fun `test approve non-pending demand fails`() {
        // Given
        val user = User(
            username = "testuser",
            email = "test@example.com",
            passwordHash = "\$2a\$10\$dummyhash"
        ).apply {
            id = 1L
        }

        val demand = Demand(
            id = 1L,
            title = "Test Demand",
            demandType = DemandType.CREATE_NEW,
            newAssetName = "Test Asset",
            newAssetType = "Server",
            newAssetOwner = "IT Team",
            status = DemandStatus.APPROVED, // Already approved
            requestor = user
        )

        val request = DemandController.ApproveDemandRequest(approved = true)

        every { demandRepository.findById(1L) } returns Optional.of(demand)

        // When
        val response = demandController.approveDemand(1L, request)

        // Then
        assertEquals(HttpStatus.BAD_REQUEST, response.status)
        verify(exactly = 0) { demandRepository.update(any<Demand>()) }
    }

    @Test
    fun `test get demand summary`() {
        // Given
        every { demandRepository.count() } returns 10L
        every { demandRepository.countByStatus(DemandStatus.PENDING) } returns 3L
        every { demandRepository.countByStatus(DemandStatus.APPROVED) } returns 4L
        every { demandRepository.countByStatus(DemandStatus.REJECTED) } returns 2L
        every { demandRepository.countByDemandType(DemandType.CHANGE) } returns 6L
        every { demandRepository.countByDemandType(DemandType.CREATE_NEW) } returns 4L

        // When
        val response = demandController.getDemandSummary()

        // Then
        assertEquals(HttpStatus.OK, response.status)
        val summary = response.body as DemandController.DemandSummary
        assertEquals(10L, summary.totalDemands)
        assertEquals(3L, summary.pendingDemands)
        assertEquals(4L, summary.approvedDemands)
        assertEquals(2L, summary.rejectedDemands)
        assertEquals(6L, summary.changeDemands)
        assertEquals(4L, summary.createNewDemands)
    }

    @Test
    fun `test get approved demands for risk assessment`() {
        // Given
        val user = User(
            username = "testuser",
            email = "test@example.com",
            passwordHash = "\$2a\$10\$dummyhash"
        ).apply {
            id = 1L
        }

        val approvedDemands = listOf(
            Demand(
                id = 1L,
                title = "Approved Demand 1",
                demandType = DemandType.CHANGE,
                status = DemandStatus.APPROVED,
                requestor = user
            ),
            Demand(
                id = 2L,
                title = "Approved Demand 2",
                demandType = DemandType.CREATE_NEW,
                status = DemandStatus.APPROVED,
                requestor = user
            )
        )

        every { demandRepository.findApprovedDemandsWithoutRiskAssessment() } returns approvedDemands

        // When
        val response = demandController.getApprovedDemandsForRiskAssessment()

        // Then
        assertEquals(HttpStatus.OK, response.status)
        val returnedDemands = response.body as List<Demand>
        assertEquals(2, returnedDemands.size)
        assertTrue(returnedDemands.all { it.status == DemandStatus.APPROVED })
    }

    @Test
    fun `test delete demand with risk assessments fails`() {
        // Given
        val user = User(
            username = "testuser",
            email = "test@example.com",
            passwordHash = "\$2a\$10\$dummyhash"
        ).apply {
            id = 1L
        }

        val demand = Demand(
            id = 1L,
            title = "Test Demand",
            demandType = DemandType.CREATE_NEW,
            newAssetName = "Test Asset",
            newAssetType = "Server",
            newAssetOwner = "IT Team",
            requestor = user
        )

        every { demandRepository.findById(1L) } returns Optional.of(demand)
        every { 
            entityManager.createQuery(
                "SELECT COUNT(ra) FROM RiskAssessment ra WHERE ra.demand.id = :demandId",
                Long::class.java
            ).setParameter("demandId", 1L).singleResult 
        } returns 1L // Has associated risk assessments

        // When
        val response = demandController.deleteDemand(1L)

        // Then
        assertEquals(HttpStatus.BAD_REQUEST, response.status)
        verify(exactly = 0) { demandRepository.delete(any<Demand>()) }
    }

    @Test
    fun `test delete demand without risk assessments succeeds`() {
        // Given
        val user = User(
            username = "testuser",
            email = "test@example.com",
            passwordHash = "\$2a\$10\$dummyhash"
        ).apply {
            id = 1L
        }

        val demand = Demand(
            id = 1L,
            title = "Test Demand",
            demandType = DemandType.CREATE_NEW,
            newAssetName = "Test Asset",
            newAssetType = "Server",
            newAssetOwner = "IT Team",
            requestor = user
        )

        every { demandRepository.findById(1L) } returns Optional.of(demand)
        every { 
            entityManager.createQuery(
                "SELECT COUNT(ra) FROM RiskAssessment ra WHERE ra.demand.id = :demandId",
                Long::class.java
            ).setParameter("demandId", 1L).singleResult 
        } returns 0L // No associated risk assessments
        every { demandRepository.delete(demand) } returns Unit

        // When
        val response = demandController.deleteDemand(1L)

        // Then
        assertEquals(HttpStatus.OK, response.status)
        verify { demandRepository.delete(demand) }
    }
}