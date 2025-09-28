package com.secman.service

import com.secman.domain.*
import com.secman.repository.DemandRepository
import com.secman.repository.AssetRepository
import com.secman.repository.UserRepository
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.util.*

@MicronautTest
class DemandServiceTest {

    private lateinit var demandRepository: DemandRepository
    private lateinit var assetRepository: AssetRepository
    private lateinit var userRepository: UserRepository

    @BeforeEach
    fun setup() {
        demandRepository = mockk()
        assetRepository = mockk()
        userRepository = mockk()
    }

    @Test
    fun `test demand validation for CHANGE type`() {
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

        val changeDemand = Demand(
            id = 1L,
            title = "Change Request",
            demandType = DemandType.CHANGE,
            existingAsset = asset,
            requestor = user
        )

        // When
        val isValid = changeDemand.validateAssetInformation()

        // Then
        assertTrue(isValid, "CHANGE demand with existing asset should be valid")
        assertEquals("Test Asset", changeDemand.getAssetName())
        assertEquals("Server", changeDemand.getAssetType())
        assertEquals("IT Team", changeDemand.getAssetOwner())
    }

    @Test
    fun `test demand validation for CREATE_NEW type`() {
        // Given
        val user = User(
            username = "testuser",
            email = "test@example.com",
            passwordHash = "\$2a\$10\$dummyhash"
        ).apply {
            id = 1L
        }

        val createNewDemand = Demand(
            id = 1L,
            title = "New Asset Request",
            demandType = DemandType.CREATE_NEW,
            newAssetName = "New Server",
            newAssetType = "Application Server",
            newAssetOwner = "Development Team",
            requestor = user
        )

        // When
        val isValid = createNewDemand.validateAssetInformation()

        // Then
        assertTrue(isValid, "CREATE_NEW demand with complete asset info should be valid")
        assertEquals("New Server", createNewDemand.getAssetName())
        assertEquals("Application Server", createNewDemand.getAssetType())
        assertEquals("Development Team", createNewDemand.getAssetOwner())
    }

    @Test
    fun `test invalid CHANGE demand without asset`() {
        // Given
        val user = User(
            username = "testuser",
            email = "test@example.com",
            passwordHash = "\$2a\$10\$dummyhash"
        ).apply {
            id = 1L
        }

        val changeDemand = Demand(
            id = 1L,
            title = "Change Request",
            demandType = DemandType.CHANGE,
            existingAsset = null,
            requestor = user
        )

        // When
        val isValid = changeDemand.validateAssetInformation()

        // Then
        assertFalse(isValid, "CHANGE demand without existing asset should be invalid")
    }

    @Test
    fun `test invalid CREATE_NEW demand with incomplete info`() {
        // Given
        val user = User(
            username = "testuser",
            email = "test@example.com",
            passwordHash = "\$2a\$10\$dummyhash"
        ).apply {
            id = 1L
        }

        val createNewDemand = Demand(
            id = 1L,
            title = "New Asset Request",
            demandType = DemandType.CREATE_NEW,
            newAssetName = "New Server",
            newAssetType = null, // Missing required field
            newAssetOwner = "Development Team",
            requestor = user
        )

        // When
        val isValid = createNewDemand.validateAssetInformation()

        // Then
        assertFalse(isValid, "CREATE_NEW demand with missing asset type should be invalid")
    }

    @Test
    fun `test demand status workflow`() {
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
            requestor = user,
            status = DemandStatus.PENDING
        )

        // When & Then
        assertEquals(DemandStatus.PENDING, demand.status)

        // Simulate approval
        demand.status = DemandStatus.APPROVED
        demand.approvedDate = LocalDateTime.now()
        assertEquals(DemandStatus.APPROVED, demand.status)
        assertNotNull(demand.approvedDate)

        // Simulate risk assessment creation
        demand.status = DemandStatus.IN_PROGRESS
        assertEquals(DemandStatus.IN_PROGRESS, demand.status)

        // Simulate completion
        demand.status = DemandStatus.COMPLETED
        assertEquals(DemandStatus.COMPLETED, demand.status)
    }

    @Test
    fun `test demand priority levels`() {
        // Test all priority levels
        val priorities = listOf(Priority.LOW, Priority.MEDIUM, Priority.HIGH, Priority.CRITICAL)
        
        priorities.forEach { priority ->
            val user = User(
                username = "testuser",
                email = "test@example.com",
                passwordHash = "\$2a\$10\$dummyhash"
            ).apply {
                id = 1L
            }

            val demand = Demand(
                id = 1L,
                title = "Priority Test",
                demandType = DemandType.CREATE_NEW,
                newAssetName = "Test Asset",
                newAssetType = "Server",
                newAssetOwner = "IT Team",
                priority = priority,
                requestor = user
            )

            assertEquals(priority, demand.priority, "Priority should be set correctly")
        }
    }

    @Test
    fun `test demand timestamps`() {
        // Given
        val user = User(
            username = "testuser",
            email = "test@example.com",
            passwordHash = "\$2a\$10\$dummyhash"
        ).apply {
            id = 1L
        }

        val demand = Demand(
            title = "Timestamp Test",
            demandType = DemandType.CREATE_NEW,
            newAssetName = "Test Asset",
            newAssetType = "Server",
            newAssetOwner = "IT Team",
            requestor = user
        )

        // When
        demand.onCreate() // Simulate @PrePersist

        // Then
        assertNotNull(demand.createdAt, "Created timestamp should be set")
        assertNotNull(demand.updatedAt, "Updated timestamp should be set")
        assertEquals(demand.createdAt, demand.updatedAt, "Initially, created and updated timestamps should be equal")

        // Simulate an update
        Thread.sleep(10) // Ensure time difference
        demand.onUpdate() // Simulate @PreUpdate

        assertNotEquals(demand.createdAt, demand.updatedAt, "After update, timestamps should be different")
    }

    @Test
    fun `test demand toString method`() {
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
            demandType = DemandType.CHANGE,
            status = DemandStatus.PENDING,
            requestor = user
        )

        // When
        val toString = demand.toString()

        // Then
        assertTrue(toString.contains("Test Demand"), "ToString should contain title")
        assertTrue(toString.contains("CHANGE"), "ToString should contain demand type")
        assertTrue(toString.contains("PENDING"), "ToString should contain status")
    }

    @Test
    fun `test demand equality`() {
        // Given
        val user = User(
            username = "testuser",
            email = "test@example.com",
            passwordHash = "\$2a\$10\$dummyhash"
        ).apply {
            id = 1L
        }

        val demand1 = Demand(
            id = 1L,
            title = "Test Demand",
            demandType = DemandType.CHANGE,
            requestor = user
        )

        val demand2 = Demand(
            id = 1L,
            title = "Different Title",
            demandType = DemandType.CREATE_NEW,
            requestor = user
        )

        val demand3 = Demand(
            id = 2L,
            title = "Test Demand",
            demandType = DemandType.CHANGE,
            requestor = user
        )

        // When & Then
        assertEquals(demand1, demand2, "Demands with same ID should be equal")
        assertNotEquals(demand1, demand3, "Demands with different IDs should not be equal")
        assertEquals(demand1.hashCode(), demand2.hashCode(), "Equal demands should have same hash code")
    }
}