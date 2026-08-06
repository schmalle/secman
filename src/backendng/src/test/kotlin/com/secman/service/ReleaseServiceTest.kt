package com.secman.service

import com.secman.domain.AssessmentBasisType
import com.secman.domain.Release
import com.secman.domain.RiskAssessment
import com.secman.domain.User
import com.secman.repository.AlignmentReviewerRepository
import com.secman.repository.AlignmentSessionRepository
import com.secman.repository.AlignmentSnapshotRepository
import com.secman.repository.ReleaseRepository
import com.secman.repository.RequirementRepository
import com.secman.repository.RequirementReviewRepository
import com.secman.repository.RequirementSnapshotRepository
import com.secman.repository.RiskAssessmentRepository
import com.secman.repository.UserRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.LocalDate
import java.util.Optional

class ReleaseServiceTest {

    private val releaseRepository = mockk<ReleaseRepository>(relaxed = true)
    private val requirementRepository = mockk<RequirementRepository>(relaxed = true)
    private val snapshotRepository = mockk<RequirementSnapshotRepository>(relaxed = true)
    private val userRepository = mockk<UserRepository>(relaxed = true)
    private val alignmentSessionRepository = mockk<AlignmentSessionRepository>(relaxed = true)
    private val alignmentSnapshotRepository = mockk<AlignmentSnapshotRepository>(relaxed = true)
    private val alignmentReviewerRepository = mockk<AlignmentReviewerRepository>(relaxed = true)
    private val requirementReviewRepository = mockk<RequirementReviewRepository>(relaxed = true)
    private val riskAssessmentRepository = mockk<RiskAssessmentRepository>(relaxed = true)

    private val service = ReleaseService(
        releaseRepository,
        requirementRepository,
        snapshotRepository,
        userRepository,
        alignmentSessionRepository,
        alignmentSnapshotRepository,
        alignmentReviewerRepository,
        requirementReviewRepository,
        riskAssessmentRepository
    )

    private val activeRelease = Release(id = 42L, version = "1.0.0", name = "e2e-awsmail-release", status = Release.ReleaseStatus.ACTIVE)
    private val someUser = User(id = 1L, username = "assessor", email = "assessor@test.com", passwordHash = "x")

    private fun riskAssessment(id: Long, lockedRelease: Release?) = RiskAssessment(
        id = id,
        startDate = LocalDate.now(),
        endDate = LocalDate.now().plusDays(7),
        assessmentBasisType = AssessmentBasisType.ASSET,
        assessmentBasisId = 1L,
        assessor = someUser,
        requestor = someUser,
        lockedRelease = lockedRelease
    )

    // --- guard against deleting an ACTIVE release ------------------------------

    @Test
    fun `deleteRelease without force refuses an ACTIVE release`() {
        every { releaseRepository.findById(42L) } returns Optional.of(activeRelease)

        val ex = assertThrows<IllegalStateException> { service.deleteRelease(42L, force = false) }
        assertThat(ex.message).contains("ACTIVE")
        verify(exactly = 0) { releaseRepository.delete(any()) }
    }

    @Test
    fun `deleteRelease force=true deletes an ACTIVE release`() {
        every { releaseRepository.findById(42L) } returns Optional.of(activeRelease)
        every { riskAssessmentRepository.findAll() } returns emptyList()

        service.deleteRelease(42L, force = true)

        verify { releaseRepository.delete(activeRelease) }
    }

    // --- FK detachment: the bug this test guards against -----------------------

    @Test
    fun `deleteRelease force=true detaches risk assessments locked to that release`() {
        // Without this detach, the FK release_id -> release.id on risk_assessment
        // makes releaseRepository.delete() throw at the DB layer even though the
        // ACTIVE guard was bypassed — exactly the failure E2E cleanup was silently
        // swallowing, leaving debris releases behind forever.
        val lockedHere = riskAssessment(100L, lockedRelease = activeRelease)
        val lockedElsewhere = riskAssessment(101L, lockedRelease = Release(id = 99L, version = "9.9.9", name = "other"))
        val unlocked = riskAssessment(102L, lockedRelease = null)

        every { releaseRepository.findById(42L) } returns Optional.of(activeRelease)
        every { riskAssessmentRepository.findAll() } returns listOf(lockedHere, lockedElsewhere, unlocked)

        service.deleteRelease(42L, force = true)

        assertThat(lockedHere.lockedRelease).isNull()
        verify { riskAssessmentRepository.update(lockedHere) }
        verify(exactly = 0) { riskAssessmentRepository.update(lockedElsewhere) }
        verify(exactly = 0) { riskAssessmentRepository.update(unlocked) }
    }

    @Test
    fun `deleteRelease without force never touches lockedRelease on non-ACTIVE releases`() {
        val archived = activeRelease.copy(status = Release.ReleaseStatus.ARCHIVED)
        every { releaseRepository.findById(42L) } returns Optional.of(archived)

        service.deleteRelease(42L, force = false)

        verify(exactly = 0) { riskAssessmentRepository.findAll() }
        verify { releaseRepository.delete(archived) }
    }

    @Test
    fun `deleteRelease throws when release does not exist`() {
        every { releaseRepository.findById(999L) } returns Optional.empty()

        assertThrows<NoSuchElementException> { service.deleteRelease(999L) }
    }
}
