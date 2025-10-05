package com.secman.service

import com.secman.domain.Release
import com.secman.domain.RequirementSnapshot
import com.secman.repository.ReleaseRepository
import com.secman.repository.RequirementSnapshotRepository
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.*

/**
 * Unit Tests for RequirementComparisonService
 * Tests: T025
 * TDD: These tests must FAIL until RequirementComparisonService is implemented
 */
class RequirementComparisonServiceTest {

    private lateinit var comparisonService: RequirementComparisonService
    private lateinit var releaseRepository: ReleaseRepository
    private lateinit var snapshotRepository: RequirementSnapshotRepository

    @BeforeEach
    fun setup() {
        releaseRepository = mockk()
        snapshotRepository = mockk()

        // This will fail until RequirementComparisonService exists
        comparisonService = RequirementComparisonService(
            releaseRepository,
            snapshotRepository
        )
    }

    @Test
    fun `compare - Added detects requirement in toRelease but not fromRelease`() {
        // Arrange
        val fromRelease = Release(id = 1L, version = "1.0.0", name = "v1")
        val toRelease = Release(id = 2L, version = "1.1.0", name = "v1.1")

        val fromSnapshots = listOf(
            RequirementSnapshot(
                id = 1L,
                release = fromRelease,
                originalRequirementId = 1L,
                shortreq = "REQ-001: Auth"
            )
        )

        val toSnapshots = listOf(
            RequirementSnapshot(
                id = 2L,
                release = toRelease,
                originalRequirementId = 1L,
                shortreq = "REQ-001: Auth"
            ),
            RequirementSnapshot(
                id = 3L,
                release = toRelease,
                originalRequirementId = 2L,
                shortreq = "REQ-002: Encryption"
            )
        )

        every { releaseRepository.findById(1L) } returns Optional.of(fromRelease)
        every { releaseRepository.findById(2L) } returns Optional.of(toRelease)
        every { snapshotRepository.findByReleaseId(1L) } returns fromSnapshots
        every { snapshotRepository.findByReleaseId(2L) } returns toSnapshots

        // Act
        val result = comparisonService.compare(1L, 2L)

        // Assert
        assertEquals(1, result.added.size)
        assertEquals(2L, result.added[0].originalRequirementId)
        assertEquals("REQ-002: Encryption", result.added[0].shortreq)
        assertEquals(0, result.deleted.size)
        assertEquals(0, result.modified.size)
        assertEquals(1, result.unchanged)
    }

    @Test
    fun `compare - Deleted detects requirement in fromRelease but not toRelease`() {
        // Arrange
        val fromRelease = Release(id = 1L, version = "1.0.0", name = "v1")
        val toRelease = Release(id = 2L, version = "1.1.0", name = "v1.1")

        val fromSnapshots = listOf(
            RequirementSnapshot(
                id = 1L,
                release = fromRelease,
                originalRequirementId = 1L,
                shortreq = "REQ-001: Auth"
            ),
            RequirementSnapshot(
                id = 2L,
                release = fromRelease,
                originalRequirementId = 2L,
                shortreq = "REQ-002: Deprecated"
            )
        )

        val toSnapshots = listOf(
            RequirementSnapshot(
                id = 3L,
                release = toRelease,
                originalRequirementId = 1L,
                shortreq = "REQ-001: Auth"
            )
        )

        every { releaseRepository.findById(1L) } returns Optional.of(fromRelease)
        every { releaseRepository.findById(2L) } returns Optional.of(toRelease)
        every { snapshotRepository.findByReleaseId(1L) } returns fromSnapshots
        every { snapshotRepository.findByReleaseId(2L) } returns toSnapshots

        // Act
        val result = comparisonService.compare(1L, 2L)

        // Assert
        assertEquals(0, result.added.size)
        assertEquals(1, result.deleted.size)
        assertEquals(2L, result.deleted[0].originalRequirementId)
        assertEquals("REQ-002: Deprecated", result.deleted[0].shortreq)
        assertEquals(0, result.modified.size)
        assertEquals(1, result.unchanged)
    }

    @Test
    fun `compare - Modified detects requirement in both with different field values`() {
        // Arrange
        val fromRelease = Release(id = 1L, version = "1.0.0", name = "v1")
        val toRelease = Release(id = 2L, version = "1.1.0", name = "v1.1")

        val fromSnapshots = listOf(
            RequirementSnapshot(
                id = 1L,
                release = fromRelease,
                originalRequirementId = 1L,
                shortreq = "REQ-001: Auth required",
                details = "Basic auth",
                motivation = "Security"
            )
        )

        val toSnapshots = listOf(
            RequirementSnapshot(
                id = 2L,
                release = toRelease,
                originalRequirementId = 1L,
                shortreq = "REQ-001: MFA required",
                details = "Multi-factor auth",
                motivation = "Security"
            )
        )

        every { releaseRepository.findById(1L) } returns Optional.of(fromRelease)
        every { releaseRepository.findById(2L) } returns Optional.of(toRelease)
        every { snapshotRepository.findByReleaseId(1L) } returns fromSnapshots
        every { snapshotRepository.findByReleaseId(2L) } returns toSnapshots

        // Act
        val result = comparisonService.compare(1L, 2L)

        // Assert
        assertEquals(0, result.added.size)
        assertEquals(0, result.deleted.size)
        assertEquals(1, result.modified.size)

        val diff = result.modified[0]
        assertEquals(1L, diff.id)
        assertEquals("REQ-001: MFA required", diff.shortreq)
        assertEquals(2, diff.changes.size)

        val shortreqChange = diff.changes.find { it.fieldName == "shortreq" }
        assertNotNull(shortreqChange)
        assertEquals("REQ-001: Auth required", shortreqChange!!.oldValue)
        assertEquals("REQ-001: MFA required", shortreqChange.newValue)

        val detailsChange = diff.changes.find { it.fieldName == "details" }
        assertNotNull(detailsChange)
        assertEquals("Basic auth", detailsChange!!.oldValue)
        assertEquals("Multi-factor auth", detailsChange.newValue)

        assertEquals(0, result.unchanged)
    }

    @Test
    fun `compare - Unchanged counts identical requirements correctly`() {
        // Arrange
        val fromRelease = Release(id = 1L, version = "1.0.0", name = "v1")
        val toRelease = Release(id = 2L, version = "1.1.0", name = "v1.1")

        val fromSnapshots = listOf(
            RequirementSnapshot(
                id = 1L,
                release = fromRelease,
                originalRequirementId = 1L,
                shortreq = "REQ-001: Auth",
                details = "Details",
                motivation = "Motivation"
            ),
            RequirementSnapshot(
                id = 2L,
                release = fromRelease,
                originalRequirementId = 2L,
                shortreq = "REQ-002: Encryption",
                details = "AES-256",
                motivation = "Security"
            )
        )

        val toSnapshots = listOf(
            RequirementSnapshot(
                id = 3L,
                release = toRelease,
                originalRequirementId = 1L,
                shortreq = "REQ-001: Auth",
                details = "Details",
                motivation = "Motivation"
            ),
            RequirementSnapshot(
                id = 4L,
                release = toRelease,
                originalRequirementId = 2L,
                shortreq = "REQ-002: Encryption",
                details = "AES-256",
                motivation = "Security"
            )
        )

        every { releaseRepository.findById(1L) } returns Optional.of(fromRelease)
        every { releaseRepository.findById(2L) } returns Optional.of(toRelease)
        every { snapshotRepository.findByReleaseId(1L) } returns fromSnapshots
        every { snapshotRepository.findByReleaseId(2L) } returns toSnapshots

        // Act
        val result = comparisonService.compare(1L, 2L)

        // Assert
        assertEquals(0, result.added.size)
        assertEquals(0, result.deleted.size)
        assertEquals(0, result.modified.size)
        assertEquals(2, result.unchanged)
    }

    @Test
    fun `compare - Field changes detects shortreq details motivation changes`() {
        // Arrange
        val fromRelease = Release(id = 1L, version = "1.0.0", name = "v1")
        val toRelease = Release(id = 2L, version = "1.1.0", name = "v1.1")

        val fromSnapshots = listOf(
            RequirementSnapshot(
                id = 1L,
                release = fromRelease,
                originalRequirementId = 1L,
                shortreq = "Old shortreq",
                details = "Old details",
                motivation = "Old motivation",
                example = "Same example",
                usecase = "Same usecase"
            )
        )

        val toSnapshots = listOf(
            RequirementSnapshot(
                id = 2L,
                release = toRelease,
                originalRequirementId = 1L,
                shortreq = "New shortreq",
                details = "New details",
                motivation = "New motivation",
                example = "Same example",
                usecase = "Same usecase"
            )
        )

        every { releaseRepository.findById(1L) } returns Optional.of(fromRelease)
        every { releaseRepository.findById(2L) } returns Optional.of(toRelease)
        every { snapshotRepository.findByReleaseId(1L) } returns fromSnapshots
        every { snapshotRepository.findByReleaseId(2L) } returns toSnapshots

        // Act
        val result = comparisonService.compare(1L, 2L)

        // Assert
        assertEquals(1, result.modified.size)
        val diff = result.modified[0]
        assertEquals(3, diff.changes.size)

        val fieldNames = diff.changes.map { it.fieldName }
        assertTrue(fieldNames.contains("shortreq"))
        assertTrue(fieldNames.contains("details"))
        assertTrue(fieldNames.contains("motivation"))
        assertFalse(fieldNames.contains("example"))
        assertFalse(fieldNames.contains("usecase"))
    }

    @Test
    fun `compare - Throws exception when fromReleaseId equals toReleaseId`() {
        // Act & Assert
        val exception = assertThrows(IllegalArgumentException::class.java) {
            comparisonService.compare(1L, 1L)
        }

        assertTrue(exception.message!!.contains("must be different") || exception.message!!.contains("same"))
    }

    @Test
    fun `compare - Throws exception when fromRelease not found`() {
        // Arrange
        every { releaseRepository.findById(999L) } returns Optional.empty()

        // Act & Assert
        val exception = assertThrows(NoSuchElementException::class.java) {
            comparisonService.compare(999L, 1L)
        }

        assertTrue(exception.message!!.contains("not found") || exception.message!!.contains("Release"))
    }
}
