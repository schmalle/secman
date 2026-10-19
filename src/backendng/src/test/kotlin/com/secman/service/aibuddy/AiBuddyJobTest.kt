package com.secman.service.aibuddy

import com.fasterxml.jackson.databind.ObjectMapper
import com.secman.config.AiBuddyConfig
import com.secman.domain.User
import com.secman.dto.*
import com.secman.security.BetaFeatureAccess
import io.micronaut.http.HttpStatus
import io.micronaut.http.exceptions.HttpStatusException
import io.micronaut.security.authentication.Authentication
import io.mockk.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future

class AiBuddyJobTest {
    private val config = AiBuddyConfig().apply { enabled = true; singleInstance = true; model = "fixture/model"; providers = listOf("fixture") }
    private val settings = mockk<AiBuddySettingsService>().also { every { it.current() } returns
        AiBuddySettings(config, "fixture", "https://openrouter.ai/api/v1", "fixture") }
    private val access = mockk<AiBuddyAccessGuard>()
    private val snapshots = mockk<AiBuddySnapshotService>()
    private val microsoft = mockk<MicrosoftRemediationEvidenceSource>()
    private val crowdstrike = mockk<CrowdStrikeRemediationEvidenceSource>()
    private val mozilla = mockk<MozillaRemediationEvidenceSource>()
    private val model = mockk<AiBuddyModelClient>()
    private val executor = mockk<ExecutorService>()
    private val task = slot<Runnable>()
    private val future = mockk<Future<*>>(relaxed = true)
    private val user = User(id = 7, username = "viewer", email = "viewer@test.invalid", passwordHash = "unused", roles = mutableSetOf(User.Role.BETA))
    private val auth = Authentication.build("viewer", listOf("BETA"), mapOf("userId" to "7"))
    private val now = Instant.now()
    private val finding = RecordedFinding(1, "CVE-2025-1111", "Windows Server 2022", "High", "CROWDSTRIKE", now, now, now, null, null, false, "INSTALLED")
    private val snapshot = AiBuddySnapshot(AiBuddySystem(2, "private-host", "Windows Server 2022"), now, now, now, listOf(finding), emptyList(), emptyList(), emptyList(), listOf("private-host"), "fingerprint")
    private val candidate = RemediationCandidate("candidate", "Windows Server 2022", AiBuddyActionType.OS_UPDATE, listOf("KB1111111"), findingRefs = setOf("f1"), evidence = emptyList(), applicability = listOf(AiBuddyCheck("OS_BUILD")))
    private val service = AiBuddyJobService(config, settings, mockk<BetaFeatureAccess>(), access, snapshots,
        microsoft, crowdstrike, mozilla, RemediationPlanner(), model, AiBuddyJson(ObjectMapper().findAndRegisterModules()), executor)
    init {
        every { access.check(2, auth) } returns user
        every { snapshots.capture(2) } returns snapshot
        every { microsoft.fetch(any(), any()) } returns RemediationEvidenceResult(listOf(candidate))
        every { crowdstrike.fetch(any(), any()) } returns RemediationEvidenceResult(emptyList())
        every { mozilla.fetch(any(), any()) } returns RemediationEvidenceResult(emptyList())
        every { model.order(any(), any()) } returns listOf(candidate.id)
        every { executor.submit(capture(task)) } returns future
    }
    private fun start() = service.start(2, UUID.randomUUID().toString(), auth)

    @Test fun `repeated clicks and request IDs admit one task and reject conflicting binding`() {
        val requestId = UUID.randomUUID().toString()
        val first = service.start(2, requestId, auth)
        assertThat(service.start(2, requestId, auth).jobId).isEqualTo(first.jobId)
        assertThat(start().jobId).isEqualTo(first.jobId)
        every { access.check(3, auth) } returns user
        assertThat(assertThrows<AiBuddyFailure> { service.start(3, requestId, auth) }.code).isEqualTo("REQUEST_ID_CONFLICT")
        verify(exactly = 1) { executor.submit(any<Runnable>()) }
    }
    @Test fun `coherent snapshot survives import and report retrieval marks current changes`() {
        val job = start()
        every { snapshots.capture(2) } returnsMany listOf(snapshot, snapshot.copy(fingerprint = "changed"), snapshot.copy(fingerprint = "changed"))
        task.captured.run()
        val report = service.report(2, job.jobId, auth)
        assertThat(report.snapshot.sourceChanged).isTrue()
        assertThat(report.scope.consideredFindingCount).isEqualTo(1)
        assertThat(report.analysis.conditionalFindingCount).isEqualTo(1)
        assertThat(report.analysis.verifiedCoveredFindingCount).isZero()
    }
    @Test fun `provider failures preserve explicitly partial validated evidence`() {
        for (code in listOf("PROVIDER_TIMEOUT", "PROVIDER_RATE_LIMITED", "PROVIDER_AUTH_FAILED", "PROVIDER_CREDIT_EXHAUSTED", "OUTPUT_INVALID")) {
            val local = AiBuddyJobService(config, settings, mockk(), access, snapshots, microsoft, crowdstrike, mozilla,
                RemediationPlanner(), model, AiBuddyJson(ObjectMapper().findAndRegisterModules()), executor)
            every { model.order(any(), any()) } throws AiBuddyFailure(code)
            val job = local.start(2, UUID.randomUUID().toString(), auth)
            task.captured.run()
            val report = local.report(2, job.jobId, auth)
            assertThat(report.analysis.completeness).isEqualTo("PARTIAL")
            assertThat(report.analysis.limitations).contains(code)
            assertThat(report.analysis.model).isNull()
        }
    }
    @Test fun `empty findings skip all paid model requests`() {
        every { snapshots.capture(2) } returns snapshot.copy(findings = emptyList())
        every { microsoft.fetch(any(), any()) } returns RemediationEvidenceResult(emptyList())
        val job = start(); task.captured.run()
        assertThat(service.report(2, job.jobId, auth).scope.consideredFindingCount).isZero()
        assertThat(service.report(2, job.jobId, auth).analysis.completeness).isEqualTo("COMPLETE_WITHIN_RECORDED_SCOPE")
        verify(exactly = 0) { model.order(any(), any()) }
    }
    @Test fun `role revocation during generation blocks publication and cached retrieval`() {
        every { model.order(any(), any()) } answers {
            every { access.check(2, auth) } throws HttpStatusException(HttpStatus.FORBIDDEN, "BETA_REQUIRED")
            listOf(candidate.id)
        }
        val job = start(); task.captured.run()
        assertThrows<HttpStatusException> { service.report(2, job.jobId, auth) }
    }
    @Test fun `changed common config aborts running jobs and labels completed reports stale`() {
        val job = start(); task.captured.run()
        every { settings.current() } returns AiBuddySettings(config, "fixture", "https://openrouter.ai/api/v1", "changed")
        assertThat(service.report(2, job.jobId, auth).snapshot.staleReasons).contains("MODEL_OR_EVIDENCE_CONFIGURATION_CHANGED")
        val next = start()
        every { settings.current() } returns AiBuddySettings(config, "fixture", "https://openrouter.ai/api/v1", "changed-again")
        task.captured.run()
        assertThat(service.status(2, next.jobId, auth).state).isEqualTo("FAILED")
        assertThat(service.status(2, next.jobId, auth).error!!.code).isEqualTo("CONFIGURATION_CHANGED")
    }
    @Test fun `owner isolation cancellation and expiry never deliver retained reports`() {
        val job = start(); task.captured.run()
        val other = Authentication.build("other", listOf("BETA"), mapOf("userId" to "8"))
        every { access.check(2, other) } returns user.copy(id = 8)
        assertThrows<HttpStatusException> { service.report(2, job.jobId, other) }
        service.cancel(2, job.jobId, auth)
        assertThat(service.status(2, job.jobId, auth).state).isEqualTo("CANCELLED")
        assertThrows<AiBuddyFailure> { service.report(2, job.jobId, auth) }
        verify { future.cancel(true) }
    }
}
