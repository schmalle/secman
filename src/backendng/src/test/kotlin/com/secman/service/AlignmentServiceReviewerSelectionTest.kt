package com.secman.service

import com.secman.domain.AlignmentReviewer
import com.secman.domain.AlignmentSession
import com.secman.domain.Release
import com.secman.domain.User
import com.secman.repository.*
import io.mockk.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.Optional

/**
 * Unit tests for reviewer selection in AlignmentService.startAlignment.
 *
 * Covers the reviewerUserIds parameter: default enrollment of all REQ-role
 * users, subset selection, and validation failures.
 */
class AlignmentServiceReviewerSelectionTest {

    private lateinit var alignmentSessionRepository: AlignmentSessionRepository
    private lateinit var alignmentReviewerRepository: AlignmentReviewerRepository
    private lateinit var alignmentSnapshotRepository: AlignmentSnapshotRepository
    private lateinit var requirementReviewRepository: RequirementReviewRepository
    private lateinit var reviewDecisionRepository: ReviewDecisionRepository
    private lateinit var releaseRepository: ReleaseRepository
    private lateinit var requirementSnapshotRepository: RequirementSnapshotRepository
    private lateinit var requirementRepository: RequirementRepository
    private lateinit var userRepository: UserRepository
    private lateinit var releaseService: ReleaseService
    private lateinit var service: AlignmentService

    private val initiator = reqUser(1L, "admin")
    private val reqAlice = reqUser(10L, "alice")
    private val reqBob = reqUser(11L, "bob")
    private val reqCarol = reqUser(12L, "carol")

    private fun reqUser(id: Long, username: String) = User(
        id = id,
        username = username,
        email = "$username@example.com",
        passwordHash = "x",
        roles = mutableSetOf(User.Role.REQ)
    )

    @BeforeEach
    fun setUp() {
        alignmentSessionRepository = mockk()
        alignmentReviewerRepository = mockk()
        alignmentSnapshotRepository = mockk()
        requirementReviewRepository = mockk()
        reviewDecisionRepository = mockk()
        releaseRepository = mockk()
        requirementSnapshotRepository = mockk()
        requirementRepository = mockk()
        userRepository = mockk()
        releaseService = mockk()

        service = AlignmentService(
            alignmentSessionRepository,
            alignmentReviewerRepository,
            alignmentSnapshotRepository,
            requirementReviewRepository,
            reviewDecisionRepository,
            releaseRepository,
            requirementSnapshotRepository,
            requirementRepository,
            userRepository,
            releaseService
        )

        val release = Release(id = 100L, version = "1.0.0", name = "Test Release")

        every { releaseRepository.findById(100L) } returns Optional.of(release)
        every { alignmentSessionRepository.hasOpenSession(100L) } returns false
        every { userRepository.findById(1L) } returns Optional.of(initiator)
        every { userRepository.findByRolesContaining(User.Role.REQ) } returns listOf(reqAlice, reqBob, reqCarol)
        every { releaseRepository.findByStatus(Release.ReleaseStatus.ACTIVE) } returns emptyList()
        every { alignmentSessionRepository.save(any()) } answers { firstArg<AlignmentSession>().also { it.id = 500L } }
        every { alignmentSessionRepository.update(any()) } answers { firstArg() }
        every { requirementRepository.findCurrentRequirements() } returns emptyList()
        every { alignmentSnapshotRepository.saveAll(any<List<com.secman.domain.AlignmentSnapshot>>()) } answers { firstArg() }
        every { alignmentReviewerRepository.save(any()) } answers { firstArg<AlignmentReviewer>() }
        every { releaseRepository.update(any<Release>()) } answers { firstArg() }
    }

    @Test
    fun `enrolls all REQ users when no reviewer selection is given`() {
        val result = service.startAlignment(100L, 1L)

        assertEquals(3, result.reviewers.size)
        assertEquals(setOf(10L, 11L, 12L), result.reviewers.map { it.user.id }.toSet())
    }

    @Test
    fun `enrolls only the selected subset of REQ users`() {
        val result = service.startAlignment(100L, 1L, reviewerUserIds = listOf(10L, 12L))

        assertEquals(2, result.reviewers.size)
        assertEquals(setOf(10L, 12L), result.reviewers.map { it.user.id }.toSet())
        verify(exactly = 2) { alignmentReviewerRepository.save(any()) }
    }

    @Test
    fun `deduplicates repeated reviewer IDs`() {
        val result = service.startAlignment(100L, 1L, reviewerUserIds = listOf(11L, 11L, 11L))

        assertEquals(1, result.reviewers.size)
        assertEquals(11L, result.reviewers.single().user.id)
    }

    @Test
    fun `rejects empty reviewer selection`() {
        val ex = assertThrows<IllegalArgumentException> {
            service.startAlignment(100L, 1L, reviewerUserIds = emptyList())
        }
        assertTrue(ex.message!!.contains("At least one reviewer"))
    }

    @Test
    fun `rejects reviewer IDs that do not exist or lack the REQ role`() {
        val ex = assertThrows<IllegalArgumentException> {
            service.startAlignment(100L, 1L, reviewerUserIds = listOf(10L, 999L))
        }
        assertTrue(ex.message!!.contains("999"))
    }

    @Test
    fun `fails when no REQ users exist regardless of selection`() {
        every { userRepository.findByRolesContaining(User.Role.REQ) } returns emptyList()

        assertThrows<IllegalStateException> {
            service.startAlignment(100L, 1L, reviewerUserIds = listOf(10L))
        }
    }

    @Test
    fun `invalid selection does not enroll any reviewers`() {
        assertThrows<IllegalArgumentException> {
            service.startAlignment(100L, 1L, reviewerUserIds = listOf(999L))
        }
        verify(exactly = 0) { alignmentReviewerRepository.save(any()) }
    }
}
