package com.secman.service

import com.secman.domain.*
import com.secman.dto.*
import com.secman.repository.AssetRepository
import com.secman.repository.UserRepository
import com.secman.testutil.BaseIntegrationTest
import com.secman.testutil.TestDataFactory
import io.micronaut.http.exceptions.HttpStatusException
import io.micronaut.security.authentication.Authentication
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import jakarta.inject.Inject
import jakarta.inject.Singleton
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Run only against an explicitly confirmed disposable TEST_DB_* database. */
@MicronautTest(environments = ["test"], transactional = false)
class IntegrationPersistenceTest : BaseIntegrationTest() {
    @Inject lateinit var users: UserRepository
    @Inject lateinit var assets: AssetRepository
    @Inject lateinit var vulnerabilities: com.secman.repository.VulnerabilityRepository
    @Inject lateinit var admin: IntegrationAdminService
    @Inject lateinit var scans: IntegrationScanService
    @Inject lateinit var reads: IntegrationReadService
    @Inject lateinit var cleanup: IntegrationPersistenceCleanup
    private lateinit var user: User
    private lateinit var asset: Asset
    private lateinit var auth: Authentication
    private var scannerId = 0L
    private var subjectId = 0L
    private val time = Instant.now().minusSeconds(3600).truncatedTo(java.time.temporal.ChronoUnit.SECONDS)

    @BeforeEach
    fun seed() {
        val suffix = UUID.randomUUID().toString()
        user = users.save(TestDataFactory.createAdminUser("integration-$suffix", "$suffix@example.test"))
        auth = Authentication.build(user.username, listOf("ADMIN", "USER"))
        asset = assets.save(TestDataFactory.createAsset(name = "integration-$suffix", owner = user.username))
        scannerId = admin.saveScanner(null, IntegrationScannerRequest("Integration persistence test", "VISUAL", user.id!!), auth).id
        subjectId = admin.bind(scannerId, IntegrationSubjectRequest(assetId = asset.id), auth)
    }

    @AfterEach
    fun removeFixture() {
        if (scannerId != 0L) cleanup.remove(scannerId, asset.id!!, user.id!!)
    }

    private fun body(index: Long, findings: List<IntegrationFindingInput> = emptyList(), status: String = "SUCCESS", complete: Boolean = true) =
        IntegrationRunRequest(scannerId, subjectId, "run-$index", status, complete, time.plusSeconds(index), time.plusSeconds(index), findings = findings)
    private fun evidence(id: String = "stable") = IntegrationFindingInput(id, severity = "HIGH", title = "Original title", evidence = "Retained evidence",
        attachments = listOf(IntegrationAttachmentInput("evidence.txt", "text/plain", "ZXZpZGVuY2U=")))

    @Test
    fun `atomic snapshots replay resolve reopen and retain historic evidence`() {
        val first = scans.submit(body(1, listOf(evidence())), auth)
        assertThat(scans.submit(body(1, listOf(evidence())), auth).replayed).isTrue()
        val original = reads.findings(0, 100, IntegrationFindingFilter(scannerId = scannerId), auth).content.single()
        scans.submit(body(2, status = "PARTIAL"), auth)
        assertThat(reads.finding(original.id, auth).state).isEqualTo("OPEN")
        assertThat(scans.submit(body(3), auth).resolved).isEqualTo(1)
        assertThat(reads.finding(original.id, auth).vulnerabilityId).isNull()
        scans.submit(body(4, listOf(evidence().copy(severity = "INFO", title = "Renamed"))), auth)
        val reopened = reads.finding(original.id, auth)
        assertThat(reopened.firstSeenAt).isEqualTo(original.firstSeenAt)
        assertThat(reopened.severity).isEqualTo("INFO")
        assertThat(reopened.vulnerabilityId).isNotEqualTo(original.vulnerabilityId)
        val historic = reads.run(first.id, auth)
        assertThat(historic.findings.single().title).isEqualTo("Original title")
        val attachment = historic.attachments.single()
        assertThat(reads.attachment(attachment.findingId, attachment.id, auth).content).isEqualTo("evidence".toByteArray())
        assertThat(reads.summary(auth).openFindings).isGreaterThanOrEqualTo(1)
        assertThat(reads.globalSummary().totalSubjects).isGreaterThanOrEqualTo(1)
    }

    @Test
    fun `invalid snapshot and replay conflict do not change prior persisted findings`() {
        scans.submit(body(1, listOf(evidence())), auth)
        assertThatThrownBy { scans.submit(body(2, listOf(evidence().copy(attachments = listOf(
            IntegrationAttachmentInput("bad.png", "image/png", "YQ=="))))), auth) }.isInstanceOf(HttpStatusException::class.java)
        assertThatThrownBy { scans.submit(body(1), auth) }.isInstanceOf(HttpStatusException::class.java)
        val findings = reads.findings(0, 100, IntegrationFindingFilter(scannerId = scannerId), auth)
        assertThat(findings.content.single().state).isEqualTo("OPEN")
        assertThat(reads.runs(0, 100, scannerId, subjectId, auth).totalElements).isEqualTo(1)
    }

    @Test
    fun `account and domain current queries retain other sources and older open scanner observations`() {
        vulnerabilities.save(Vulnerability(asset = asset, vulnerabilityId = "MANUAL-OBSERVATION", source = "CLI_MANUAL",
            cvssSeverity = "High", scanTimestamp = java.time.LocalDateTime.now()))
        scans.submit(body(1, listOf(evidence("first"))), auth)
        scans.submit(body(2, listOf(evidence("second")), status = "PARTIAL"), auth)
        val current = vulnerabilities.findLatestVulnerabilitiesForAssetIds(setOf(asset.id!!))
        assertThat(current).hasSize(3)
        assertThat(current.map { it.vulnerabilityId }).contains("MANUAL-OBSERVATION")
        val counts = vulnerabilities.countLatestVulnerabilitiesBySeverityForAssetIds(setOf(asset.id!!))
        assertThat(counts.single().totalCount).isEqualTo(java.math.BigInteger.valueOf(3))
    }

    @Test
    fun `concurrent identical submissions commit once and replay once`() {
        val gate = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val request = body(1, listOf(evidence()))
            val futures = (1..2).map { executor.submit<IntegrationRunAck> { gate.await(); scans.submit(request, auth) } }
            gate.countDown()
            val results = futures.map { it.get(30, TimeUnit.SECONDS) }
            assertThat(results.map { it.id }.toSet()).hasSize(1)
            assertThat(results.count { it.replayed }).isEqualTo(1)
            assertThat(reads.runs(0, 100, scannerId, subjectId, auth).totalElements).isEqualTo(1)
            assertThat(reads.findings(0, 100, IntegrationFindingFilter(scannerId = scannerId), auth).totalElements).isEqualTo(1)
        } finally { executor.shutdownNow() }
    }

    @Test
    fun `all detail and attachment reads enforce asset scope`() {
        val accepted = scans.submit(body(1, listOf(evidence())), auth)
        val finding = reads.findings(0, 100, IntegrationFindingFilter(scannerId = scannerId), auth).content.single()
        val stranger = Authentication.build("unmapped-" + UUID.randomUUID(), listOf("USER"),
            mapOf("userId" to -1L, "email" to "unmapped@example.test"))
        assertThat(reads.findings(0, 100, IntegrationFindingFilter(), stranger).content).isEmpty()
        assertThatThrownBy { reads.finding(finding.id, stranger) }.isInstanceOf(HttpStatusException::class.java)
        assertThatThrownBy { reads.run(accepted.id, stranger) }.isInstanceOf(HttpStatusException::class.java)
        assertThatThrownBy { reads.attachment(finding.id, finding.attachments.single().id, stranger) }.isInstanceOf(HttpStatusException::class.java)
    }
}

@Singleton
open class IntegrationPersistenceCleanup(private val em: EntityManager) {
    @Transactional
    open fun remove(scannerId: Long, assetId: Long, userId: Long) {
        em.createQuery("DELETE FROM IntegrationAttachment a WHERE a.runId IN (SELECT r.id FROM IntegrationRun r WHERE r.scannerId = :scanner)")
            .setParameter("scanner", scannerId).executeUpdate()
        em.createQuery("DELETE FROM IntegrationFinding f WHERE f.subjectId IN (SELECT s.id FROM IntegrationSubject s WHERE s.scannerId = :scanner)")
            .setParameter("scanner", scannerId).executeUpdate()
        em.createQuery("DELETE FROM IntegrationRun r WHERE r.scannerId = :scanner").setParameter("scanner", scannerId).executeUpdate()
        em.createQuery("DELETE FROM IntegrationSubject s WHERE s.scannerId = :scanner").setParameter("scanner", scannerId).executeUpdate()
        em.createQuery("DELETE FROM IntegrationScanner s WHERE s.id = :scanner").setParameter("scanner", scannerId).executeUpdate()
        em.createQuery("DELETE FROM Vulnerability v WHERE v.asset.id = :asset").setParameter("asset", assetId).executeUpdate()
        em.remove(em.find(Asset::class.java, assetId))
        em.remove(em.find(User::class.java, userId))
    }
}
