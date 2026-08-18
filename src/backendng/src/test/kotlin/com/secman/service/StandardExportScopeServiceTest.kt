package com.secman.service

import com.secman.domain.Release
import com.secman.domain.Requirement
import com.secman.domain.Standard
import com.secman.domain.UseCase
import com.secman.repository.ReleaseRepository
import com.secman.repository.RequirementRepository
import com.secman.repository.StandardRepository
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.Optional

/**
 * Resolution rules for the public standard/release export parameters.
 *
 * These are the checks a caller of `GET /api/requirements/export/docx?standard=…&release=…`
 * depends on, so they are unit tested against mocked repositories rather than through HTTP.
 */
class StandardExportScopeServiceTest {

    private val standardRepository = mockk<StandardRepository>(relaxed = true)
    private val releaseRepository = mockk<ReleaseRepository>(relaxed = true)
    private val requirementRepository = mockk<RequirementRepository>(relaxed = true)
    private val releaseScopeService = mockk<ReleaseRequirementScopeService>(relaxed = true)

    private val service = StandardExportScopeService(
        standardRepository, releaseRepository, requirementRepository, releaseScopeService
    )

    private val itSecurity = UseCase(id = 1L, name = "IT Security - All requirements")
    private val otSecurity = UseCase(id = 2L, name = "OT Security")
    private val unrelated = UseCase(id = 3L, name = "Unrelated")

    private val standard = Standard(
        id = 10L,
        name = "IT/OT Security",
        useCases = mutableSetOf(itSecurity, otSecurity)
    )

    private val activeRelease = Release(
        id = 42L,
        version = "98.739714.0",
        name = "e2e-awsmail-release",
        status = Release.ReleaseStatus.ACTIVE
    )

    private val archivedRelease = Release(
        id = 7L,
        version = "1.0.0",
        name = "first",
        status = Release.ReleaseStatus.ARCHIVED
    )

    private fun requirement(id: Long, chapter: String? = null) =
        Requirement(id = id, internalId = "REQ-$id", shortreq = "req $id", chapter = chapter)

    // ---------- standard resolution ----------

    @Test
    fun `resolves a standard by id`() {
        every { standardRepository.findByIdWithUseCases(10L) } returns listOf(standard)

        val resolution = service.resolve(10L, null, null, null)

        assertThat(resolution).isInstanceOf(StandardExportScopeService.Resolution.Resolved::class.java)
        val resolved = resolution as StandardExportScopeService.Resolution.Resolved
        assertThat(resolved.standard).isEqualTo(standard)
        assertThat(resolved.release).isNull()
    }

    @Test
    fun `resolves a standard by name, ignoring case`() {
        every { standardRepository.findByNameIgnoreCaseWithUseCases("it/ot security") } returns listOf(standard)

        val resolved = service.resolve(null, "it/ot security", null, null)
            as StandardExportScopeService.Resolution.Resolved

        assertThat(resolved.standard?.name).isEqualTo("IT/OT Security")
    }

    @Test
    fun `trims surrounding whitespace off a standard name`() {
        every { standardRepository.findByNameIgnoreCaseWithUseCases("IT/OT Security") } returns listOf(standard)

        val resolved = service.resolve(null, "  IT/OT Security  ", null, null)
            as StandardExportScopeService.Resolution.Resolved

        assertThat(resolved.standard).isEqualTo(standard)
    }

    @Test
    fun `id wins when both id and name are supplied`() {
        every { standardRepository.findByIdWithUseCases(10L) } returns listOf(standard)

        val resolved = service.resolve(10L, "Some Other Standard", null, null)
            as StandardExportScopeService.Resolution.Resolved

        assertThat(resolved.standard).isEqualTo(standard)
    }

    @Test
    fun `an unknown standard id or name is a 404, not an empty export`() {
        every { standardRepository.findByIdWithUseCases(999L) } returns emptyList()
        every { standardRepository.findByNameIgnoreCaseWithUseCases(any()) } returns emptyList()

        assertThat(service.resolve(999L, null, null, null))
            .isInstanceOf(StandardExportScopeService.Resolution.Missing::class.java)
        assertThat(service.resolve(null, "No Such Standard", null, null))
            .isInstanceOf(StandardExportScopeService.Resolution.Missing::class.java)
    }

    @Test
    fun `an over-long standard name is rejected without reaching the database`() {
        val resolution = service.resolve(null, "x".repeat(300), null, null)

        assertThat(resolution).isInstanceOf(StandardExportScopeService.Resolution.Missing::class.java)
        io.mockk.verify(exactly = 0) { standardRepository.findByNameIgnoreCaseWithUseCases(any()) }
    }

    @Test
    fun `a blank standard name means no standard filter at all`() {
        val resolved = service.resolve(null, "   ", null, null)
            as StandardExportScopeService.Resolution.Resolved

        assertThat(resolved.standard).isNull()
        assertThat(resolved.release).isNull()
    }

    // ---------- release resolution ----------

    @Test
    fun `release=latest resolves to the ACTIVE release`() {
        every { releaseScopeService.findActiveRelease() } returns activeRelease

        val resolved = service.resolve(null, null, null, "latest")
            as StandardExportScopeService.Resolution.Resolved

        assertThat(resolved.release).isEqualTo(activeRelease)
    }

    @Test
    fun `release=LATEST is accepted in any case`() {
        every { releaseScopeService.findActiveRelease() } returns activeRelease

        val resolved = service.resolve(null, null, null, "LaTeSt")
            as StandardExportScopeService.Resolution.Resolved

        assertThat(resolved.release).isEqualTo(activeRelease)
    }

    @Test
    fun `release=latest with no ACTIVE release is a 404, never the live corpus`() {
        every { releaseScopeService.findActiveRelease() } returns null

        val resolution = service.resolve(null, null, null, "latest")

        assertThat(resolution).isInstanceOf(StandardExportScopeService.Resolution.Missing::class.java)
        assertThat((resolution as StandardExportScopeService.Resolution.Missing).message)
            .isEqualTo("No active release")
    }

    @Test
    fun `a release version string resolves to that release`() {
        every { releaseRepository.findByVersion("1.0.0") } returns Optional.of(archivedRelease)

        val resolved = service.resolve(null, null, null, "1.0.0")
            as StandardExportScopeService.Resolution.Resolved

        assertThat(resolved.release).isEqualTo(archivedRelease)
    }

    @Test
    fun `an unknown release version or id is a 404`() {
        every { releaseRepository.findByVersion(any()) } returns Optional.empty()
        every { releaseRepository.findById(any()) } returns Optional.empty()

        assertThat(service.resolve(null, null, null, "9.9.9"))
            .isInstanceOf(StandardExportScopeService.Resolution.Missing::class.java)
        assertThat(service.resolve(null, null, 123L, null))
            .isInstanceOf(StandardExportScopeService.Resolution.Missing::class.java)
    }

    @Test
    fun `releaseId and release agreeing on the same release is accepted`() {
        every { releaseRepository.findById(42L) } returns Optional.of(activeRelease)
        every { releaseScopeService.findActiveRelease() } returns activeRelease

        val resolved = service.resolve(null, null, 42L, "latest")
            as StandardExportScopeService.Resolution.Resolved

        assertThat(resolved.release).isEqualTo(activeRelease)
    }

    @Test
    fun `releaseId and release pointing at different releases is a 400`() {
        every { releaseRepository.findById(7L) } returns Optional.of(archivedRelease)
        every { releaseScopeService.findActiveRelease() } returns activeRelease

        val resolution = service.resolve(null, null, 7L, "latest")

        assertThat(resolution).isInstanceOf(StandardExportScopeService.Resolution.Invalid::class.java)
    }

    @Test
    fun `no scope parameters at all resolves to the live full corpus`() {
        val resolved = service.resolve(null, null, null, null)
            as StandardExportScopeService.Resolution.Resolved

        assertThat(resolved.standard).isNull()
        assertThat(resolved.release).isNull()
    }

    // ---------- requirement selection ----------

    @Test
    fun `a live standard export unions its use cases and de-duplicates`() {
        val shared = requirement(1L, chapter = "A")
        every { requirementRepository.findByUsecaseId(1L) } returns listOf(shared, requirement(2L, "B"))
        every { requirementRepository.findByUsecaseId(2L) } returns listOf(shared, requirement(3L, "C"))

        val requirements = service.requirementsFor(standard, null)

        assertThat(requirements.map { it.id }).containsExactly(1L, 2L, 3L)
    }

    @Test
    fun `a live standard export is ordered by chapter then id`() {
        every { requirementRepository.findByUsecaseId(1L) } returns listOf(
            requirement(9L, chapter = "B"),
            requirement(3L, chapter = "A"),
            requirement(1L, chapter = "B")
        )
        every { requirementRepository.findByUsecaseId(2L) } returns emptyList()

        val requirements = service.requirementsFor(standard, null)

        assertThat(requirements.map { it.id }).containsExactly(3L, 1L, 9L)
    }

    @Test
    fun `a release-scoped standard export reads the frozen snapshots, not the live rows`() {
        val frozen = listOf(requirement(5L))
        every { releaseScopeService.requirementsForRelease(42L, listOf(1L, 2L)) } returns frozen

        val requirements = service.requirementsFor(standard, activeRelease)

        assertThat(requirements).isEqualTo(frozen)
        io.mockk.verify(exactly = 0) { requirementRepository.findByUsecaseId(any()) }
    }

    @Test
    fun `a standard with no use cases exports nothing rather than everything`() {
        val empty = Standard(id = 11L, name = "Unmapped", useCases = mutableSetOf())

        assertThat(service.requirementsFor(empty, null)).isEmpty()
        assertThat(service.requirementsFor(empty, activeRelease)).isEmpty()
        io.mockk.verify(exactly = 0) { requirementRepository.findByUsecaseId(any()) }
    }

    // ---------- allRequirements ----------

    /**
     * The reason the flag exists rather than a "tick every use case" button in the UI: a
     * requirement carrying no use case at all is unreachable through the union, however many
     * boxes are ticked. On the instance this was built for that was 22 of 168 rows — a standard
     * that looked complete and silently was not.
     */
    @Test
    fun `an all-requirements standard covers rows that no use case reaches`() {
        val everything = Standard(id = 20L, name = "Everything", allRequirements = true)
        val orphan = requirement(99L, chapter = "Z")  // belongs to no use case
        every { requirementRepository.findAll() } returns listOf(requirement(1L, "A"), orphan)

        val requirements = service.requirementsFor(everything, null)

        assertThat(requirements.map { it.id }).containsExactly(1L, 99L)
        io.mockk.verify(exactly = 0) { requirementRepository.findByUsecaseId(any()) }
    }

    @Test
    fun `an all-requirements standard ignores its use cases without discarding them`() {
        // The selection is kept so unticking the flag restores the subset instead of emptying it.
        val everything = Standard(
            id = 21L, name = "Everything", useCases = mutableSetOf(itSecurity), allRequirements = true
        )
        every { requirementRepository.findAll() } returns listOf(requirement(1L), requirement(2L))

        assertThat(service.requirementsFor(everything, null).map { it.id }).containsExactly(1L, 2L)
        assertThat(everything.useCases).containsExactly(itSecurity)
        io.mockk.verify(exactly = 0) { requirementRepository.findByUsecaseId(any()) }
    }

    @Test
    fun `an all-requirements standard still exports everything when it has no use cases`() {
        // Without the flag this is the "unmapped standard" case that deliberately exports nothing.
        // With it, empty use cases are irrelevant — which is exactly why the guard had to move.
        val everything = Standard(id = 22L, name = "Everything", allRequirements = true)
        every { requirementRepository.findAll() } returns listOf(requirement(1L))

        assertThat(service.requirementsFor(everything, null).map { it.id }).containsExactly(1L)
    }

    @Test
    fun `an all-requirements standard is ordered like any other export`() {
        val everything = Standard(id = 23L, name = "Everything", allRequirements = true)
        every { requirementRepository.findAll() } returns listOf(
            requirement(9L, chapter = "B"), requirement(3L, chapter = "A"), requirement(1L, chapter = "B")
        )

        assertThat(service.requirementsFor(everything, null).map { it.id }).containsExactly(3L, 1L, 9L)
    }

    @Test
    fun `a release-scoped all-requirements export reads the release, not the live corpus`() {
        // Reproducibility: the export must not change because someone added a requirement today.
        val everything = Standard(id = 24L, name = "Everything", allRequirements = true)
        val frozen = listOf(requirement(5L), requirement(6L))
        every { releaseScopeService.allRequirementsForRelease(42L) } returns frozen

        val requirements = service.requirementsFor(everything, activeRelease)

        assertThat(requirements).isEqualTo(frozen)
        io.mockk.verify(exactly = 0) { requirementRepository.findAll() }
        io.mockk.verify(exactly = 0) { releaseScopeService.requirementsForRelease(any<Long>(), any<Collection<Long>>()) }
    }

    @Test
    fun `a standard without the flag is completely unaffected`() {
        // Regression guard: the default must keep the existing union behaviour byte for byte.
        assertThat(standard.allRequirements).isFalse()
        every { requirementRepository.findByUsecaseId(1L) } returns listOf(requirement(1L))
        every { requirementRepository.findByUsecaseId(2L) } returns listOf(requirement(2L))

        assertThat(service.requirementsFor(standard, null).map { it.id }).containsExactly(1L, 2L)
        io.mockk.verify(exactly = 0) { requirementRepository.findAll() }
    }

    @Test
    fun `a use case not on the standard contributes nothing`() {
        every { requirementRepository.findByUsecaseId(1L) } returns listOf(requirement(1L))
        every { requirementRepository.findByUsecaseId(2L) } returns emptyList()

        val requirements = service.requirementsFor(standard, null)

        assertThat(requirements.map { it.id }).containsExactly(1L)
        io.mockk.verify(exactly = 0) { requirementRepository.findByUsecaseId(unrelated.id!!) }
    }

    // ---------- filename and title ----------

    @Test
    fun `the filename strips the slash and space out of a standard name`() {
        val filename = service.exportFilename(standard, activeRelease, "docx")

        // "IT/OT Security" must not reach a Content-Disposition header intact; the release
        // version keeps its dots so it stays recognisable.
        assertThat(filename).startsWith("requirements_ITOT_Security_v98.739714.0_")
        assertThat(filename).endsWith(".docx")
        assertThat(filename).doesNotContain("/")
    }

    @Test
    fun `the filename cannot carry a quote or newline into the header`() {
        val hostile = Standard(id = 12L, name = "evil\"\r\nX-Injected: 1", useCases = mutableSetOf())

        val filename = service.exportFilename(hostile, null, "xlsx")

        assertThat(filename).doesNotContain("\"")
        assertThat(filename).doesNotContain("\r")
        assertThat(filename).doesNotContain("\n")
        assertThat(filename).endsWith(".xlsx")
    }

    @Test
    fun `a name with no usable characters still yields a filename`() {
        val symbols = Standard(id = 13L, name = "///", useCases = mutableSetOf())

        assertThat(service.exportFilename(symbols, null, "docx")).startsWith("requirements_standard_")
    }

    @Test
    fun `a dotted name cannot come back out looking like a relative path`() {
        val traversal = Standard(id = 14L, name = "../../etc/passwd", useCases = mutableSetOf())

        val filename = service.exportFilename(traversal, null, "docx")

        assertThat(filename).doesNotContain("..")
        assertThat(filename).doesNotContain("/")
        assertThat(filename).isEqualTo(
            "requirements_etcpasswd_${java.time.LocalDateTime.now().format(
                java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd")
            )}.docx"
        )
    }

    @Test
    fun `the title names the standard and, when frozen, the release`() {
        assertThat(service.exportTitle(standard, null)).isEqualTo("IT/OT Security")
        assertThat(service.exportTitle(standard, activeRelease))
            .isEqualTo("IT/OT Security - Release 98.739714.0")
    }
}
