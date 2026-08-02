package com.secman.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.secman.domain.Release
import com.secman.domain.RequirementSnapshot
import com.secman.repository.ReleaseRepository
import com.secman.repository.RequirementSnapshotRepository
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ReleaseRequirementScopeServiceTest {

    private val releaseRepository = mockk<ReleaseRepository>(relaxed = true)
    private val snapshotRepository = mockk<RequirementSnapshotRepository>(relaxed = true)
    private val service = ReleaseRequirementScopeService(
        releaseRepository, snapshotRepository, ObjectMapper()
    )

    private val release = Release(id = 42L, version = "2.3.0", name = "Q3 baseline")

    private fun snapshot(
        originalId: Long,
        shortreq: String,
        useCaseIdsJson: String?,
        chapter: String? = null,
        revision: Int = 1
    ) = RequirementSnapshot(
        id = originalId + 10_000,
        release = release,
        originalRequirementId = originalId,
        internalId = "REQ-$originalId",
        revision = revision,
        shortreq = shortreq,
        chapter = chapter,
        usecaseIdsSnapshot = useCaseIdsJson
    )

    @BeforeEach
    fun setup() {
        every { releaseRepository.findByStatus(Release.ReleaseStatus.ACTIVE) } returns listOf(release)
    }

    // --- findActiveRelease ----------------------------------------------------

    @Test
    fun `findActiveRelease returns the single ACTIVE release`() {
        assertThat(service.findActiveRelease()).isEqualTo(release)
    }

    @Test
    fun `findActiveRelease returns null when no release is ACTIVE`() {
        every { releaseRepository.findByStatus(Release.ReleaseStatus.ACTIVE) } returns emptyList()

        assertThat(service.findActiveRelease()).isNull()
    }

    // --- use case scoping -----------------------------------------------------

    @Test
    fun `matches only the exact use case id, not a substring of the JSON array`() {
        // Regression guard: the previous implementation did a raw
        // usecaseIdsSnapshot.contains("1"), so use case 1 matched a snapshot
        // tagged [11,12] and pulled unrelated requirements into a questionnaire.
        every { snapshotRepository.findByReleaseId(42L) } returns listOf(
            snapshot(1L, "tagged with use case 1", "[1,5]"),
            snapshot(2L, "tagged with use cases 11 and 12", "[11,12]")
        )

        val result = service.requirementsForRelease(42L, 1L)

        assertThat(result).hasSize(1)
        assertThat(result.single().shortreq).isEqualTo("tagged with use case 1")
    }

    @Test
    fun `snapshots with no or unparseable use case tags are excluded`() {
        every { snapshotRepository.findByReleaseId(42L) } returns listOf(
            snapshot(1L, "no tags", null),
            snapshot(2L, "blank tags", ""),
            snapshot(3L, "broken json", "not-json"),
            snapshot(4L, "tagged", "[7]")
        )

        val result = service.requirementsForRelease(42L, 7L)

        assertThat(result.map { it.shortreq }).containsExactly("tagged")
    }

    @Test
    fun `multi use case query returns the union, de-duplicated`() {
        every { snapshotRepository.findByReleaseId(42L) } returns listOf(
            snapshot(1L, "a", "[7]"),
            snapshot(2L, "b", "[8]"),
            // Tagged with both — must appear once, not twice.
            snapshot(3L, "c", "[7,8]"),
            snapshot(4L, "d", "[9]")
        )

        val result = service.requirementsForRelease(42L, listOf(7L, 8L))

        assertThat(result.map { it.shortreq }).containsExactlyInAnyOrder("a", "b", "c")
    }

    @Test
    fun `multi use case query with no use cases returns nothing`() {
        assertThat(service.requirementsForRelease(42L, emptyList())).isEmpty()
    }

    @Test
    fun `results are ordered by chapter then id`() {
        every { snapshotRepository.findByReleaseId(42L) } returns listOf(
            snapshot(3L, "c", "[7]", chapter = "2"),
            snapshot(1L, "a", "[7]", chapter = "1"),
            snapshot(2L, "b", "[7]", chapter = "1")
        )

        val result = service.requirementsForRelease(42L, 7L)

        assertThat(result.map { it.shortreq }).containsExactly("a", "b", "c")
    }

    // --- rehydration ----------------------------------------------------------

    @Test
    fun `rehydrated requirement keeps the original requirement id and the frozen revision`() {
        // The id must be the LIVE requirement id: Response.requirement_id is a foreign
        // key to `requirement`, so a snapshot id here would break answer persistence.
        val result = service.snapshotToRequirement(
            snapshot(55L, "Encrypt data at rest", "[7]", revision = 4)
        )

        assertThat(result.id).isEqualTo(55L)
        assertThat(result.versionNumber).isEqualTo(4)
        assertThat(result.internalId).isEqualTo("REQ-55")
        assertThat(result.idRevision).isEqualTo("REQ-55.4")
        assertThat(result.shortreq).isEqualTo("Encrypt data at rest")
    }
}
