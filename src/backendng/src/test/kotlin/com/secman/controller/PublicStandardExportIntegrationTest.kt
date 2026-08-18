package com.secman.controller

import com.secman.domain.Release
import com.secman.domain.Requirement
import com.secman.domain.RequirementSnapshot
import com.secman.domain.Standard
import com.secman.domain.UseCase
import com.secman.repository.ReleaseRepository
import com.secman.repository.RequirementRepository
import com.secman.repository.RequirementSnapshotRepository
import com.secman.repository.StandardRepository
import com.secman.repository.UseCaseRepository
import com.secman.testutil.BaseIntegrationTest
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpStatus
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.http.client.exceptions.HttpClientResponseException
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The public standard download surface, exercised WITHOUT a credential.
 *
 * Every request here deliberately carries no Authorization header and no cookie: the point of
 * the feature is that an unauthenticated caller can fetch a standard, and the point of these
 * tests is that the `@Secured` wiring actually permits that while leaving the authenticated
 * standard endpoints closed.
 */
@MicronautTest(environments = ["test"], transactional = false)
class PublicStandardExportIntegrationTest : BaseIntegrationTest() {

    @Inject
    @field:Client("/")
    lateinit var client: HttpClient

    @Inject
    lateinit var standardRepository: StandardRepository

    @Inject
    lateinit var useCaseRepository: UseCaseRepository

    @Inject
    lateinit var requirementRepository: RequirementRepository

    @Inject
    lateinit var releaseRepository: ReleaseRepository

    @Inject
    lateinit var snapshotRepository: RequirementSnapshotRepository

    @Inject
    lateinit var scopeService: com.secman.service.StandardExportScopeService

    private lateinit var standardName: String
    private lateinit var useCase: UseCase
    private lateinit var standard: Standard
    private lateinit var inScope: Requirement

    @BeforeEach
    fun setUp() {
        snapshotRepository.deleteAll()
        releaseRepository.deleteAll()
        standardRepository.deleteAll()
        requirementRepository.deleteAll()
        useCaseRepository.deleteAll()

        val suffix = System.nanoTime()
        // A slash in the name is the case the whole query-parameter design exists for.
        standardName = "IT/OT Security $suffix"
        useCase = useCaseRepository.save(UseCase(name = "IT Security $suffix"))
        val otherUseCase = useCaseRepository.save(UseCase(name = "Unrelated $suffix"))

        inScope = requirementRepository.save(
            Requirement(
                internalId = "PSE-A-${suffix % 1_000_000_000L}",
                shortreq = "In scope requirement",
                chapter = "1 Access",
                usecases = mutableSetOf(useCase)
            )
        )
        requirementRepository.save(
            Requirement(
                internalId = "PSE-B-${suffix % 1_000_000_000L}",
                shortreq = "Out of scope requirement",
                chapter = "2 Other",
                usecases = mutableSetOf(otherUseCase)
            )
        )

        standard = standardRepository.save(
            Standard(name = standardName, description = "secret internal note", useCases = mutableSetOf(useCase))
        )
    }

    private fun getBytes(uri: String) =
        client.toBlocking().exchange(HttpRequest.GET<Any>(uri), ByteArray::class.java)

    /**
     * Percent-encode a query value. `URLEncoder` emits `+` for a space (form encoding); using
     * it here asserts that the server decodes that form, which is what `URLSearchParams` in
     * the frontend and a hand-written `curl` URL both produce.
     */
    private fun enc(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")

    private fun activeRelease(version: String): Release {
        val release = releaseRepository.save(
            Release(version = version, name = "public export test", status = Release.ReleaseStatus.ACTIVE)
        )
        // Only the in-scope requirement is frozen, tagged with the standard's use case.
        snapshotRepository.save(
            RequirementSnapshot(
                release = release,
                originalRequirementId = inScope.id!!,
                internalId = inScope.internalId,
                revision = 1,
                shortreq = inScope.shortreq,
                chapter = inScope.chapter,
                usecaseIdsSnapshot = "[${useCase.id}]"
            )
        )
        return release
    }

    // ---------- the public standard list ----------

    @Test
    fun `the public standard list is readable anonymously and carries id and name only`() {
        val response = client.toBlocking().exchange(HttpRequest.GET<Any>("/api/standards/public"), String::class.java)

        assertThat(response.status).isEqualTo(HttpStatus.OK)
        val body = response.body()!!
        assertThat(body).contains(standardName)
        // The entity's other fields stay behind authentication.
        assertThat(body).doesNotContain("secret internal note")
        assertThat(body).doesNotContain("useCases")
        assertThat(body).doesNotContain("createdAt")
    }

    @Test
    fun `the full standard endpoint stays closed to anonymous callers`() {
        assertThatThrownBy { client.toBlocking().exchange(HttpRequest.GET<Any>("/api/standards"), String::class.java) }
            .isInstanceOf(HttpClientResponseException::class.java)
            .extracting { (it as HttpClientResponseException).status }
            .isEqualTo(HttpStatus.UNAUTHORIZED)
    }

    // ---------- standard-scoped export ----------

    @Test
    fun `a standard exports as Word by name without any credential`() {
        val response = getBytes("/api/requirements/export/docx?standard=${enc(standardName)}")

        assertThat(response.status).isEqualTo(HttpStatus.OK)
        assertThat(response.body()).isNotEmpty()
        val disposition = response.header("Content-Disposition")!!
        assertThat(disposition).contains("attachment")
        // The slash and space in the standard name never reach the header.
        assertThat(disposition).doesNotContain("IT/OT Security")
    }

    @Test
    fun `a standard exports as Excel by id without any credential`() {
        val response = getBytes("/api/requirements/export/xlsx?standardId=${standard.id}")

        assertThat(response.status).isEqualTo(HttpStatus.OK)
        assertThat(response.body()).isNotEmpty()
    }

    @Test
    fun `the standard name match ignores case`() {
        val response = getBytes("/api/requirements/export/docx?standard=${enc(standardName.lowercase())}")

        assertThat(response.status).isEqualTo(HttpStatus.OK)
    }

    @Test
    fun `an unknown standard is a 404`() {
        assertThatThrownBy { getBytes("/api/requirements/export/docx?standard=No%20Such%20Standard") }
            .isInstanceOf(HttpClientResponseException::class.java)
            .extracting { (it as HttpClientResponseException).status }
            .isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `a standard with no use cases reports no requirements instead of exporting everything`() {
        val unmapped = standardRepository.save(Standard(name = "Unmapped ${System.nanoTime()}", useCases = mutableSetOf()))

        val response = client.toBlocking()
            .exchange(HttpRequest.GET<Any>("/api/requirements/export/docx?standardId=${unmapped.id}"), String::class.java)

        assertThat(response.status).isEqualTo(HttpStatus.OK)
        assertThat(response.body()).contains("No requirements found for this standard")
    }

    /**
     * The counterpart to the test above, and the reason `allRequirements` is a stored flag rather
     * than a "tick every use case" button: a requirement carrying no use case is unreachable
     * through the union no matter how many boxes are ticked, so only an explicit flag can express
     * "this standard is everything".
     *
     * Note this is the one case where an export legitimately returns the full corpus. The guard it
     * sits next to — an unmapped standard exporting nothing — still holds, because that standard
     * does not carry the flag.
     */
    @Test
    fun `an all-requirements standard exports rows no use case reaches`() {
        val suffix = System.nanoTime()
        val orphan = requirementRepository.save(
            Requirement(
                internalId = "PSE-C-${suffix % 1_000_000_000L}",
                shortreq = "Orphan requirement with no use case",
                chapter = "3 Orphan",
                usecases = mutableSetOf()
            )
        )
        val everything = standardRepository.save(
            Standard(name = "Everything $suffix", useCases = mutableSetOf(), allRequirements = true)
        )

        val response = client.toBlocking().exchange(
            HttpRequest.GET<Any>("/api/requirements/export/xlsx?standardId=${everything.id}"),
            ByteArray::class.java
        )

        assertThat(response.status).isEqualTo(HttpStatus.OK)
        // An XLSX is a ZIP; the row text is not greppable, so assert on the count the export
        // resolved rather than the bytes.
        assertThat(scopeService.requirementsFor(everything, null).map { it.id })
            .describedAs("the orphan and both use-case-tagged rows must all be covered")
            .contains(orphan.id, inScope.id)
            .hasSize(requirementRepository.count().toInt())
    }

    @Test
    fun `clearing the flag returns a standard to its use-case subset`() {
        val everything = standardRepository.save(
            Standard(name = "Switchable ${System.nanoTime()}", useCases = mutableSetOf(useCase), allRequirements = true)
        )
        assertThat(scopeService.requirementsFor(everything, null))
            .hasSize(requirementRepository.count().toInt())

        // The use cases were kept while the flag was set, so this is a real toggle, not a reset.
        everything.allRequirements = false
        standardRepository.update(everything)

        assertThat(scopeService.requirementsFor(everything, null).map { it.id })
            .containsExactly(inScope.id)
    }

    // ---------- release selection ----------

    @Test
    fun `release=latest resolves to the ACTIVE release`() {
        activeRelease("9.9.${System.nanoTime() % 1000}")

        val response = getBytes("/api/requirements/export/docx?standard=${enc(standardName)}&release=latest")

        assertThat(response.status).isEqualTo(HttpStatus.OK)
        assertThat(response.body()).isNotEmpty()
    }

    @Test
    fun `release=latest without an ACTIVE release is a 404 rather than the live corpus`() {
        // No release created in this test, and setUp cleared them.
        assertThatThrownBy { getBytes("/api/requirements/export/docx?standard=${enc(standardName)}&release=latest") }
            .isInstanceOf(HttpClientResponseException::class.java)
            .extracting { (it as HttpClientResponseException).status }
            .isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `an explicit release version resolves`() {
        val version = "3.2.${System.nanoTime() % 1000}"
        activeRelease(version)

        val response = getBytes("/api/requirements/export/xlsx?standard=${enc(standardName)}&release=$version")

        assertThat(response.status).isEqualTo(HttpStatus.OK)
    }

    @Test
    fun `an unknown release version is a 404`() {
        assertThatThrownBy { getBytes("/api/requirements/export/docx?release=0.0.0-nope") }
            .isInstanceOf(HttpClientResponseException::class.java)
            .extracting { (it as HttpClientResponseException).status }
            .isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `releaseId disagreeing with release is a 400, not a silent winner`() {
        val active = activeRelease("4.1.${System.nanoTime() % 1000}")
        val other = releaseRepository.save(
            Release(version = "4.0.${System.nanoTime() % 1000}", name = "older", status = Release.ReleaseStatus.ARCHIVED)
        )
        assertThat(other.id).isNotEqualTo(active.id)

        assertThatThrownBy { getBytes("/api/requirements/export/docx?releaseId=${other.id}&release=latest") }
            .isInstanceOf(HttpClientResponseException::class.java)
            .extracting { (it as HttpClientResponseException).status }
            .isEqualTo(HttpStatus.BAD_REQUEST)
    }

    @Test
    fun `releaseId agreeing with release is accepted`() {
        val active = activeRelease("5.0.${System.nanoTime() % 1000}")

        val response = getBytes("/api/requirements/export/docx?releaseId=${active.id}&release=latest")

        assertThat(response.status).isEqualTo(HttpStatus.OK)
    }

    // ---------- unchanged behaviour ----------

    @Test
    fun `an export with no scope parameters still returns the whole live set`() {
        val response = getBytes("/api/requirements/export/docx")

        assertThat(response.status).isEqualTo(HttpStatus.OK)
        assertThat(response.body()).isNotEmpty()
    }
}
