package com.secman.service

import com.secman.domain.Release
import com.secman.domain.Requirement
import com.secman.domain.RequirementSnapshot
import com.secman.domain.User
import com.secman.repository.ReleaseRepository
import com.secman.repository.RequirementRepository
import com.secman.repository.RequirementSnapshotRepository
import com.secman.repository.UserRepository
import io.micronaut.security.authentication.Authentication
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.*

/**
 * Unit Tests for ReleaseService
 * Tests: T019-T022
 * TDD: These tests must FAIL until ReleaseService is implemented
 */
class ReleaseServiceTest {

    private lateinit var releaseService: ReleaseService
    private lateinit var releaseRepository: ReleaseRepository
    private lateinit var requirementRepository: RequirementRepository
    private lateinit var snapshotRepository: RequirementSnapshotRepository
    private lateinit var userRepository: UserRepository

    @BeforeEach
    fun setup() {
        releaseRepository = mockk()
        requirementRepository = mockk()
        snapshotRepository = mockk()
        userRepository = mockk()

        // This will fail until ReleaseService exists
        releaseService = ReleaseService(
            releaseRepository,
            requirementRepository,
            snapshotRepository,
            userRepository
        )
    }

    // ========== T019: ReleaseService.createRelease() Tests ==========

    @Test
    fun `createRelease - Success creates release and snapshots all current requirements`() {
        // Arrange
        val version = "1.0.0"
        val name = "Q4 2024 Release"
        val description = "Annual compliance review"
        val mockUser = mockk<User>(relaxed = true) {
            every { id } returns 1L
            every { username } returns "admin@example.com"
        }
        val mockAuth = mockk<Authentication> {
            every { name } returns "admin@example.com"
        }

        every { userRepository.findByUsername("admin@example.com") } returns java.util.Optional.of(mockUser)

        // Mock 3 current requirements
        val req1 = Requirement(
            id = 1L,
            shortreq = "REQ-001: Auth required",
            details = "All users must authenticate",
            language = "en"
        )
        val req2 = Requirement(
            id = 2L,
            shortreq = "REQ-002: Encryption",
            details = "Data must be encrypted",
            language = "en"
        )
        val req3 = Requirement(
            id = 3L,
            shortreq = "REQ-003: Logging",
            details = "All actions must be logged",
            language = "en"
        )

        every { releaseRepository.existsByVersion(version) } returns false
        every { releaseRepository.save(any<Release>()) } answers {
            val release = firstArg<Release>()
            release.apply { id = 100L }
        }
        every { requirementRepository.findCurrentRequirements() } returns listOf(req1, req2, req3)
        every { snapshotRepository.saveAll(any<List<RequirementSnapshot>>()) } answers {
            firstArg<List<RequirementSnapshot>>()
        }
        every { snapshotRepository.countByReleaseId(100L) } returns 3L

        // Act
        val result = releaseService.createRelease(version, name, description, mockAuth)

        // Assert
        assertNotNull(result)
        assertEquals(version, result.version)
        assertEquals(name, result.name)
        assertEquals(description, result.description)
        assertEquals(Release.ReleaseStatus.DRAFT, result.status)
        assertNotNull(result.id)

        // Verify repository interactions
        verify { releaseRepository.existsByVersion(version) }
        verify { releaseRepository.save(any<Release>()) }
        verify { requirementRepository.findCurrentRequirements() }

        val snapshotSlot = slot<List<RequirementSnapshot>>()
        verify { snapshotRepository.saveAll(capture(snapshotSlot)) }

        val snapshots = snapshotSlot.captured
        assertEquals(3, snapshots.size)
        assertEquals(1L, snapshots[0].originalRequirementId)
        assertEquals("REQ-001: Auth required", snapshots[0].shortreq)
    }

    @Test
    fun `createRelease - Validation rejects duplicate version`() {
        // Arrange
        val version = "1.0.0"
        val mockAuth = mockk<Authentication>(relaxed = true)

        every { releaseRepository.existsByVersion(version) } returns true

        // Act & Assert
        val exception = assertThrows(IllegalArgumentException::class.java) {
            releaseService.createRelease(version, "Duplicate", null, mockAuth)
        }

        assertTrue(exception.message!!.contains("version") || exception.message!!.contains("exists"))

        verify { releaseRepository.existsByVersion(version) }
        verify(exactly = 0) { releaseRepository.save(any<Release>()) }
    }

    @Test
    fun `createRelease - Validation rejects invalid version format`() {
        // Arrange
        val invalidVersions = listOf("v1.0", "1.0", "Q4-2024", "1.0.0-alpha", "1.x.0")
        val mockAuth = mockk<Authentication>(relaxed = true)

        for (invalidVersion in invalidVersions) {
            // Act & Assert
            val exception = assertThrows(IllegalArgumentException::class.java) {
                releaseService.createRelease(invalidVersion, "Test", null, mockAuth)
            }

            assertTrue(
                exception.message!!.contains("semantic versioning") ||
                exception.message!!.contains("format") ||
                exception.message!!.contains("MAJOR.MINOR.PATCH")
            )
        }
    }

    @Test
    fun `createRelease - Edge case creates release with 0 requirements`() {
        // Arrange
        val version = "1.0.0"
        val mockUser = mockk<User>(relaxed = true) {
            every { id } returns 1L
            every { username } returns "admin@example.com"
        }
        val mockAuth = mockk<Authentication> {
            every { name } returns "admin@example.com"
        }

        every { userRepository.findByUsername("admin@example.com") } returns java.util.Optional.of(mockUser)

        every { releaseRepository.existsByVersion(version) } returns false
        every { releaseRepository.save(any<Release>()) } answers {
            firstArg<Release>().apply { id = 100L }
        }
        every { requirementRepository.findCurrentRequirements() } returns emptyList()
        every { snapshotRepository.saveAll(any<List<RequirementSnapshot>>()) } returns emptyList()
        every { snapshotRepository.countByReleaseId(100L) } returns 0L

        // Act
        val result = releaseService.createRelease(version, "Empty Release", null, mockAuth)

        // Assert
        assertNotNull(result)
        assertEquals(version, result.version)

        // Should still create release even with 0 requirements
        verify { releaseRepository.save(any<Release>()) }
        verify { snapshotRepository.saveAll(emptyList()) }
    }

    @Test
    fun `createRelease - Snapshot accuracy copies all requirement fields correctly`() {
        // Arrange
        val version = "1.0.0"
        val mockUser = mockk<User>(relaxed = true) {
            every { id } returns 1L
            every { username } returns "admin@example.com"
        }
        val mockAuth = mockk<Authentication> {
            every { name } returns "admin@example.com"
        }

        every { userRepository.findByUsername("admin@example.com") } returns java.util.Optional.of(mockUser)

        val requirement = Requirement(
            id = 1L,
            shortreq = "SEC-001: Authentication",
            details = "Detailed auth requirements",
            language = "en",
            example = "Example: JWT tokens",
            motivation = "Security best practice",
            usecase = "User login flow",
            norm = "ISO 27001",
            chapter = "6.2.1"
        )

        every { releaseRepository.existsByVersion(version) } returns false
        every { releaseRepository.save(any<Release>()) } answers {
            firstArg<Release>().apply { id = 100L }
        }
        every { requirementRepository.findCurrentRequirements() } returns listOf(requirement)

        val snapshotSlot = slot<List<RequirementSnapshot>>()
        every { snapshotRepository.saveAll(capture(snapshotSlot)) } answers {
            firstArg<List<RequirementSnapshot>>()
        }
        every { snapshotRepository.countByReleaseId(100L) } returns 1L

        // Act
        releaseService.createRelease(version, "Test", null, mockAuth)

        // Assert - verify snapshot has all fields from requirement
        val snapshot = snapshotSlot.captured.first()
        assertEquals(requirement.id, snapshot.originalRequirementId)
        assertEquals(requirement.shortreq, snapshot.shortreq)
        assertEquals(requirement.details, snapshot.details)
        assertEquals(requirement.language, snapshot.language)
        assertEquals(requirement.example, snapshot.example)
        assertEquals(requirement.motivation, snapshot.motivation)
        assertEquals(requirement.usecase, snapshot.usecase)
        assertEquals(requirement.norm, snapshot.norm)
        assertEquals(requirement.chapter, snapshot.chapter)
        assertNotNull(snapshot.snapshotTimestamp)
    }

    // ========== T021: ReleaseService.deleteRelease() Tests ==========

    @Test
    fun `deleteRelease - Success deletes release and cascade deletes snapshots`() {
        // Arrange
        val releaseId = 100L
        val release = Release(
            id = releaseId,
            version = "1.0.0",
            name = "Test Release"
        )

        every { releaseRepository.findById(releaseId) } returns Optional.of(release)
        every { releaseRepository.delete(release) } returns Unit

        // Act
        releaseService.deleteRelease(releaseId)

        // Assert
        verify { releaseRepository.findById(releaseId) }
        verify { releaseRepository.delete(release) }
        // Cascade delete of snapshots is handled by database FK constraint
    }

    @Test
    fun `deleteRelease - Error when release not found`() {
        // Arrange
        val releaseId = 999L

        every { releaseRepository.findById(releaseId) } returns Optional.empty()

        // Act & Assert
        val exception = assertThrows(NoSuchElementException::class.java) {
            releaseService.deleteRelease(releaseId)
        }

        assertTrue(exception.message!!.contains("not found") || exception.message!!.contains("Release"))

        verify { releaseRepository.findById(releaseId) }
        verify(exactly = 0) { releaseRepository.delete(any()) }
    }
}
