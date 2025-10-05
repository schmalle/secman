package com.secman.service

import com.secman.domain.Release
import com.secman.domain.Requirement
import com.secman.domain.RequirementSnapshot
import com.secman.repository.RequirementRepository
import com.secman.repository.RequirementSnapshotRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.*

/**
 * Unit Tests for RequirementService - Deletion Prevention
 * Tests: T023
 * TDD: These tests must FAIL until deletion prevention is implemented
 */
class RequirementServiceTest {

    private lateinit var requirementService: RequirementService
    private lateinit var requirementRepository: RequirementRepository
    private lateinit var snapshotRepository: RequirementSnapshotRepository

    @BeforeEach
    fun setup() {
        requirementRepository = mockk()
        snapshotRepository = mockk()

        requirementService = RequirementService(
            requirementRepository,
            snapshotRepository
        )
    }

    // ========== T023: Deletion Prevention Tests ==========

    @Test
    fun `deleteRequirement - Blocked when requirement frozen in 1 release`() {
        // Arrange
        val requirementId = 1L
        val release = Release(id = 100L, version = "1.0.0", name = "Release 1")
        val snapshot = RequirementSnapshot(
            id = 1L,
            release = release,
            originalRequirementId = requirementId,
            shortreq = "REQ-001: Auth"
        )

        every { snapshotRepository.findByOriginalRequirementId(requirementId) } returns listOf(snapshot)

        // Act & Assert
        val exception = assertThrows(IllegalStateException::class.java) {
            requirementService.deleteRequirement(requirementId)
        }

        assertTrue(
            exception.message!!.contains("Cannot delete requirement") &&
            exception.message!!.contains("frozen in releases") &&
            exception.message!!.contains("1.0.0")
        )

        verify { snapshotRepository.findByOriginalRequirementId(requirementId) }
        verify(exactly = 0) { requirementRepository.deleteById(any()) }
    }

    @Test
    fun `deleteRequirement - Blocked when requirement frozen in multiple releases lists all`() {
        // Arrange
        val requirementId = 1L
        val release1 = Release(id = 100L, version = "1.0.0", name = "Release 1")
        val release2 = Release(id = 101L, version = "1.1.0", name = "Release 2")
        val release3 = Release(id = 102L, version = "2.0.0", name = "Release 3")

        val snapshot1 = RequirementSnapshot(
            id = 1L,
            release = release1,
            originalRequirementId = requirementId,
            shortreq = "REQ-001: Auth"
        )
        val snapshot2 = RequirementSnapshot(
            id = 2L,
            release = release2,
            originalRequirementId = requirementId,
            shortreq = "REQ-001: Auth"
        )
        val snapshot3 = RequirementSnapshot(
            id = 3L,
            release = release3,
            originalRequirementId = requirementId,
            shortreq = "REQ-001: Auth"
        )

        every { snapshotRepository.findByOriginalRequirementId(requirementId) } returns
            listOf(snapshot1, snapshot2, snapshot3)

        // Act & Assert
        val exception = assertThrows(IllegalStateException::class.java) {
            requirementService.deleteRequirement(requirementId)
        }

        val errorMessage = exception.message!!
        assertTrue(errorMessage.contains("Cannot delete requirement"))
        assertTrue(errorMessage.contains("frozen in releases"))
        assertTrue(errorMessage.contains("1.0.0"))
        assertTrue(errorMessage.contains("1.1.0"))
        assertTrue(errorMessage.contains("2.0.0"))

        verify { snapshotRepository.findByOriginalRequirementId(requirementId) }
        verify(exactly = 0) { requirementRepository.deleteById(any()) }
    }

    @Test
    fun `deleteRequirement - Success when requirement not in any release`() {
        // Arrange
        val requirementId = 1L

        every { snapshotRepository.findByOriginalRequirementId(requirementId) } returns emptyList()
        every { requirementRepository.existsById(requirementId) } returns true
        every { requirementRepository.deleteById(requirementId) } returns Unit

        // Act
        val result = requirementService.deleteRequirement(requirementId)

        // Assert
        assertTrue(result)

        verify { snapshotRepository.findByOriginalRequirementId(requirementId) }
        verify { requirementRepository.existsById(requirementId) }
        verify { requirementRepository.deleteById(requirementId) }
    }

    @Test
    fun `deleteRequirement - Returns false when requirement does not exist`() {
        // Arrange
        val requirementId = 999L

        every { snapshotRepository.findByOriginalRequirementId(requirementId) } returns emptyList()
        every { requirementRepository.existsById(requirementId) } returns false

        // Act
        val result = requirementService.deleteRequirement(requirementId)

        // Assert
        assertFalse(result)

        verify { snapshotRepository.findByOriginalRequirementId(requirementId) }
        verify { requirementRepository.existsById(requirementId) }
        verify(exactly = 0) { requirementRepository.deleteById(any()) }
    }
}
