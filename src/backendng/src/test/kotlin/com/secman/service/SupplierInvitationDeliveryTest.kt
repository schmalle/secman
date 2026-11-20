package com.secman.service

import com.secman.domain.RiskAssessment
import com.secman.domain.SupplierAssessmentInvitation
import com.secman.dto.SupplierQuestion
import com.secman.dto.SupplierQuestionnaire
import com.secman.repository.SupplierAssessmentInvitationRepository
import io.micronaut.security.authentication.Authentication
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import java.time.Instant
import java.util.Optional
import java.util.UUID

class SupplierInvitationDeliveryTest {
    private val repository = mockk<SupplierAssessmentInvitationRepository>()
    private val workflow = mockk<AssessmentWorkflowService>()
    private val supplierAssessments = mockk<SupplierAssessmentService>()
    private val portal = mockk<SupplierPortalClient>()
    private val email = mockk<EmailService>()
    private val templates = EmailTemplateRenderer()
    private val outbox = SupplierInvitationOutbox(repository, workflow, supplierAssessments, portal, enabled = true)
    private val worker = SupplierInvitationDelivery(repository, outbox, portal, email, templates)
    private val actor = Authentication.build("manager", listOf("ADMIN"), mapOf("userId" to 4L))
    private val row = SupplierAssessmentInvitation(
        id = 1, externalId = UUID.randomUUID().toString(), assessmentId = 2, releaseId = 3, generation = 1,
        recipientEmail = "supplier@example.test", recipientUserId = null, requesterLabel = "Procurement",
        serviceScope = "Payroll", snapshotJson = "[]", snapshotHash = "a".repeat(64),
        createdAt = Instant.now(), deadline = Instant.now().plusSeconds(3600)
    )
    private val questionnaire = SupplierQuestionnaire(
        1, row.externalId, 1, row.requesterLabel, row.serviceScope, row.deadline, row.snapshotHash,
        listOf(SupplierQuestion(UUID.randomUUID().toString(), "Do you review access?"))
    )

    init {
        val assessment = mockk<RiskAssessment>()
        every { workflow.locked(row.assessmentId) } returns assessment
        every { workflow.findLocked(row.assessmentId) } returns assessment
        every { workflow.requireAssessmentManager(actor) } returns actor
        every { repository.findFirstByAssessmentIdOrderByGenerationDesc(row.assessmentId) } returns row
        every { repository.findById(1L) } returns Optional.of(row)
        every { supplierAssessments.publicQuestionnaire(row) } returns questionnaire
        every { portal.ready() } returns true
        every { portal.invitationLink(row.externalId, any()) } answers {
            "https://portal.example.test/#${row.externalId}.${secondArg<String>()}"
        }
    }

    @Test
    fun `retry provisioning with the same verifier and never resend ambiguous mail`() {
        outbox.enqueue(row.assessmentId, row.externalId, actor)
        val credential = requireNotNull(row.deliveryCredential)
        val verifier = requireNotNull(row.deliveryVerifier)
        assertTrue(BCryptPasswordEncoder().matches(credential, verifier))
        assertNotNull(row.deliveryExpiresAt)
        every { portal.provision(questionnaire, verifier, null, false, requireNotNull(row.deliveryExpiresAt)) } returnsMany listOf(false, true)
        every { email.sendTrackedSupplierInvitation(row.recipientEmail, any(), any(), any()) } answers {
            assertTrue(thirdArg<String>().contains("#${row.externalId}.$credential"))
            assertNull(row.deliveryCredential)
            EmailService.TrackedDelivery("PENDING", "DELIVERY_UNCERTAIN")
        }

        worker.deliver(requireNotNull(outbox.claim(1, row.assessmentId)))
        assertEquals("PROVISION_PENDING", row.deliveryState)
        verify(exactly = 0) { email.sendTrackedSupplierInvitation(any(), any(), any(), any()) }

        row.deliveryDueAt = Instant.EPOCH
        worker.deliver(requireNotNull(outbox.claim(1, row.assessmentId)))
        assertEquals("UNCERTAIN", row.deliveryState)
        assertNull(row.deliveryDueAt)
        assertNull(row.deliveryCredential)
        assertNull(row.deliveryVerifier)
        assertNull(outbox.claim(1, row.assessmentId))
        verify(exactly = 1) { email.sendTrackedSupplierInvitation(any(), any(), any(), any()) }
    }

    @Test
    fun `an interrupted SMTP send becomes uncertain without another send`() {
        outbox.enqueue(row.assessmentId, row.externalId, actor)
        val job = requireNotNull(outbox.claim(1, row.assessmentId))
        assertNotNull(outbox.provisioned(job, true))
        row.deliveryDueAt = Instant.EPOCH
        assertNull(outbox.claim(1, row.assessmentId))
        assertEquals("UNCERTAIN", row.deliveryState)
        outbox.mailed(job, "SENT")
        assertEquals("UNCERTAIN", row.deliveryState)
    }

    @Test
    fun `revocation wins over a stale provisioning result`() {
        outbox.enqueue(row.assessmentId, row.externalId, actor)
        val job = requireNotNull(outbox.claim(1, row.assessmentId))
        row.revokedAt = Instant.now()
        row.deliveryState = "REVOKE_PENDING"
        row.deliveryLease = null
        row.deliveryDueAt = Instant.EPOCH
        assertNull(outbox.provisioned(job, true))
        every { portal.revoke(row.externalId) } returns true
        worker.deliver(requireNotNull(outbox.claim(1, row.assessmentId)))
        assertEquals("REVOKED", row.deliveryState)
        verify(exactly = 0) { email.sendTrackedSupplierInvitation(any(), any(), any(), any()) }
    }

    @Test
    fun `integration destination must be a fixed public HTTPS address`() {
        assertEquals("8.8.8.8", SupplierPortalClient.validateIntegrationOrigin("https://8.8.8.8:9443").host)
        listOf(
            "http://8.8.8.8", "https://localhost", "https://127.0.0.1", "https://10.0.0.1",
            "https://192.168.0.1", "https://169.254.169.254", "https://100.64.0.1",
            "https://8.8.8.8/path", "https://portal.example.test", "https://somebody@8.8.8.8",
            "https://8.8.8.8?query", "https://8.8.8.8#fragment"
        ).forEach { unsafe ->
            assertThrows(IllegalArgumentException::class.java) { SupplierPortalClient.validateIntegrationOrigin(unsafe) }
        }
    }
}
