package com.secman.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.secman.domain.*
import com.secman.dto.*
import com.secman.repository.IntegrationRepository
import com.secman.repository.UserRepository
import com.secman.testutil.TestDataFactory
import io.micronaut.http.exceptions.HttpStatusException
import io.micronaut.security.authentication.Authentication
import io.mockk.*
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Optional

class IntegrationRunWriterTest {
    private val repository = mockk<IntegrationRepository>()
    private val filters = mockk<AssetFilterService>()
    private val users = mockk<UserRepository>()
    private val exceptions = mockk<ExceptionMaterializationService>()
    private val access = IntegrationAccessService(filters, users)
    private val writer = IntegrationRunWriter(repository, access, exceptions)
    private val validator = IntegrationRunValidator(ObjectMapper().findAndRegisterModules())
    private val time = Instant.parse("2026-09-06T10:00:00Z")
    private val user = TestDataFactory.createAdminUser().also { it.id = 10 }
    private val auth = Authentication.build(user.username, listOf("ADMIN"))
    private val scanner = IntegrationScanner(id = 1, name = "scanner", source = "VISUAL", serviceUserId = 10)
    private val subject = IntegrationSubject(id = 2, scannerId = 1, assetId = 3)
    private val asset = TestDataFactory.createAsset().also { it.id = 3 }
    private val findings = mutableMapOf<String, IntegrationFinding>()
    private val runs = mutableMapOf<String, IntegrationRun>()
    private val projections = mutableMapOf<Long, Vulnerability>()
    private val attachments = mutableListOf<IntegrationAttachment>()
    private var nextId = 100L

    @BeforeEach
    fun setUp() {
        every { repository.scannerForSubmission(1) } returns scanner
        every { repository.lock(IntegrationSubject::class.java, 2) } returns subject
        every { repository.lock(Asset::class.java, 3) } returns asset
        every { filters.canAccessAsset(3, auth) } returns true
        every { users.findByUsername(user.username) } returns Optional.of(user)
        every { repository.replay(1, 2, any()) } answers { runs[thirdArg()] }
        every { repository.finding(2, any()) } answers { findings[secondArg()] }
        every { repository.find(Vulnerability::class.java, any()) } answers { projections[secondArg()] }
        every { repository.openFindingPage(2, any()) } answers {
            findings.values.filter { it.state == "OPEN" && it.id!! > secondArg<Long>() }.sortedBy { it.id }.take(500)
        }
        every { repository.persist(any()) } answers {
            when (val entity = firstArg<Any>()) {
                is IntegrationFinding -> { entity.id = nextId++; findings[entity.externalId] = entity }
                is IntegrationRun -> { entity.id = nextId++; runs[entity.runKey] = entity }
                is Vulnerability -> { entity.id = nextId++; projections[entity.id!!] = entity }
                is IntegrationAttachment -> { entity.id = nextId++; attachments += entity }
            }
        }
        every { repository.remove(any()) } answers { projections.remove(firstArg<Vulnerability>().id); Unit }
        every { repository.flush() } just Runs
        every { exceptions.recomputeForAsset(3) } returns 0
        every { repository.legacyCandidates(3, any()) } returns emptyList()
        every { repository.projectionClaimed(any()) } returns false
        every { repository.legacyIdentityClaimed(3, any()) } returns false
    }

    private fun run(offset: Long, status: String = "SUCCESS", complete: Boolean = true, present: List<IntegrationFindingInput> = emptyList()) =
        IntegrationRunRequest(1, 2, "key-$offset", status, complete, time.plusSeconds(offset), time.plusSeconds(offset), findings = present)
    private fun finding() = IntegrationFindingInput("stable", severity = "HIGH", title = "Title", evidence = "Original",
        attachments = listOf(IntegrationAttachmentInput("evidence.txt", "text/plain", "ZXZpZGVuY2U=")))
    private fun submit(request: IntegrationRunRequest) = writer.apply(validator.validate(request, time.plusSeconds(1000)), auth)

    @Test
    fun `same key is replayed without mutation but changed content conflicts`() {
        val request = run(1, present = listOf(finding()))
        val first = submit(request)
        val replay = submit(request)
        assertThat(replay.id).isEqualTo(first.id)
        assertThat(replay.replayed).isTrue()
        assertThat(runs).hasSize(1)
        assertThat(attachments).hasSize(1)
        assertThatThrownBy { submit(request.copy(completeCoverage = false)) }.isInstanceOf(HttpStatusException::class.java)
    }

    @Test
    fun `partial failed skipped incomplete and older scans preserve missing findings`() {
        submit(run(1, present = listOf(finding())))
        submit(run(2, "PARTIAL"))
        submit(run(3, "FAILED"))
        submit(run(4, "SKIPPED"))
        submit(run(5, complete = false))
        assertThat(findings.getValue("stable").state).isEqualTo("OPEN")
        assertThat(projections).hasSize(1)
        assertThatThrownBy { submit(run(0)) }.isInstanceOf(HttpStatusException::class.java)
        assertThat(subject.lastScanAt).isEqualTo(time.plusSeconds(5))
        assertThat(findings.getValue("stable").state).isEqualTo("OPEN")
    }

    @Test
    fun `complete zero resolves projection retains evidence and reopen preserves identity and SLA`() {
        submit(run(1, present = listOf(finding())))
        val initial = projections.values.single()
        val key = initial.vulnerabilityId
        val firstSeen = initial.firstSeenAt
        assertThat(submit(run(2)).resolved).isEqualTo(1)
        assertThat(projections).isEmpty()
        assertThat(findings.getValue("stable").evidence).isEqualTo("Original")
        assertThat(attachments).hasSize(1)
        submit(run(3, present = listOf(finding().copy(severity = "INFO", title = "Changed title"))))
        val reopened = projections.values.single()
        assertThat(reopened.id).isNotEqualTo(initial.id)
        assertThat(reopened.vulnerabilityId).isEqualTo(key)
        assertThat(reopened.firstSeenAt).isEqualTo(firstSeen)
        assertThat(reopened.cvssSeverity).isEqualTo("Informational")
        assertThat(findings.getValue("stable").resolvedAt).isNull()
    }

    @Test
    fun `legacy adoption preserves exception identity product and earliest first seen`() {
        val oldTime = LocalDateTime.ofInstant(time.minusSeconds(5000), ZoneOffset.UTC)
        val legacy = Vulnerability(id = 44, asset = asset, vulnerabilityId = "LEGACY-1", source = "CLI_MANUAL",
            scanTimestamp = oldTime, firstSeenAt = oldTime, vulnerableProductVersions = "Original product")
        projections[44] = legacy
        every { repository.legacyCandidates(3, listOf("LEGACY-1")) } returns listOf(legacy)
        submit(run(1, present = listOf(finding().copy(legacyIds = listOf("LEGACY-1")))))
        assertThat(projections).hasSize(1)
        assertThat(legacy.vulnerabilityId).isEqualTo("LEGACY-1")
        assertThat(legacy.vulnerableProductVersions).isEqualTo("Original product")
        submit(run(2))
        submit(run(3, present = listOf(finding())))
        assertThat(projections.values.single().vulnerabilityId).isEqualTo("LEGACY-1")
        assertThat(projections.values.single().firstSeenAt).isEqualTo(oldTime)
    }

    @Test
    fun `legacy identities already claimed by resolved findings cannot be adopted twice`() {
        every { repository.legacyIdentityClaimed(3, listOf("LEGACY-1")) } returns true
        assertThatThrownBy { submit(run(1, present = listOf(finding().copy(legacyIds = listOf("LEGACY-1"))))) }
            .isInstanceOf(HttpStatusException::class.java)
        assertThat(projections).isEmpty()
    }

    @Test
    fun `cross scanner assignment disabled scanner and out of scope assets are denied before writes`() {
        scanner.serviceUserId = 99
        assertThatThrownBy { submit(run(1)) }.isInstanceOf(HttpStatusException::class.java)
        scanner.serviceUserId = 10; scanner.enabled = false
        assertThatThrownBy { submit(run(1)) }.isInstanceOf(HttpStatusException::class.java)
        scanner.enabled = true; subject.scannerId = 99
        assertThatThrownBy { submit(run(1)) }.isInstanceOf(HttpStatusException::class.java)
        subject.scannerId = 1
        every { filters.canAccessAsset(3, auth) } returns false
        assertThatThrownBy { submit(run(1)) }.isInstanceOf(HttpStatusException::class.java)
        verify(exactly = 0) { repository.persist(any()) }
    }
}
