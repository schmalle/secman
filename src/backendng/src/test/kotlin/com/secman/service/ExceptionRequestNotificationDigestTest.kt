package com.secman.service

import com.secman.config.AppConfig
import com.secman.config.BackendConfig
import com.secman.domain.Asset
import com.secman.domain.ExceptionRequestStatus
import com.secman.domain.User
import com.secman.domain.Vulnerability
import com.secman.domain.VulnerabilityException
import com.secman.domain.VulnerabilityExceptionRequest
import com.secman.repository.UserRepository
import io.mockk.*
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.util.concurrent.CompletableFuture

/**
 * Unit tests for the pooled digest path: ExceptionRequestNotificationService.notifyAdminsOfPendingDigest.
 *
 * Verifies the core anti-flood property: N pending requests collapse to ONE email per
 * ADMIN/SECCHAMPION reviewer (not N).
 */
class ExceptionRequestNotificationDigestTest {

    private lateinit var emailService: EmailService
    private lateinit var userRepository: UserRepository
    private lateinit var appConfig: AppConfig
    private lateinit var service: ExceptionRequestNotificationService

    @BeforeEach
    fun setUp() {
        emailService = mockk(relaxed = false)
        userRepository = mockk()
        appConfig = mockk()
        val backendCfg = mockk<BackendConfig>()
        every { backendCfg.baseUrl } returns "https://secman.example.com/"
        every { appConfig.backend } returns backendCfg
        service = ExceptionRequestNotificationService(emailService, userRepository, appConfig)
    }

    private fun user(name: String, role: User.Role) = User(
        username = name,
        email = "$name@example.com",
        passwordHash = "x",
        roles = mutableSetOf(role)
    )

    private fun request(cve: String, asset: String, requester: String): VulnerabilityExceptionRequest {
        val assetEntity = Asset(name = asset, type = "SERVER", owner = "owner")
        val vuln = Vulnerability(
            asset = assetEntity,
            vulnerabilityId = cve,
            scanTimestamp = LocalDateTime.of(2026, 6, 18, 8, 0)
        )
        return VulnerabilityExceptionRequest(
            requestedByUsername = requester,
            subject = VulnerabilityException.Subject.CVE,
            scope = VulnerabilityException.Scope.ASSET,
            reason = "A sufficiently long business justification that satisfies the minimum length rule.",
            expirationDate = LocalDateTime.now().plusDays(30),
            status = ExceptionRequestStatus.PENDING,
            cveId = cve,
            assetId = 1L,
            vulnerability = vuln
        ).apply { createdAt = LocalDateTime.of(2026, 6, 18, 9, 0) }
    }

    @Test
    fun `sends exactly one digest email per reviewer regardless of request count`() {
        every { userRepository.findAll() } returns listOf(
            user("admin", User.Role.ADMIN),
            user("champ", User.Role.SECCHAMPION),
            user("regular", User.Role.USER) // must be excluded
        )
        every {
            emailService.sendEmailWithInlineImages(any(), any(), any(), any(), any())
        } returns CompletableFuture.completedFuture(true)

        val requests = (1..100).map { request("CVE-2026-$it", "asset-$it", "alice") }

        val result = service.notifyAdminsOfPendingDigest(requests).get()

        assertTrue(result)
        // 100 requests -> exactly 2 emails (one per ADMIN + SECCHAMPION), NOT 200.
        verify(exactly = 2) { emailService.sendEmailWithInlineImages(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `digest body lists every request and a correct count`() {
        every { userRepository.findAll() } returns listOf(user("admin", User.Role.ADMIN))
        val subjectSlot = slot<String>()
        val textSlot = slot<String>()
        val htmlSlot = slot<String>()
        every {
            emailService.sendEmailWithInlineImages(any(), capture(subjectSlot), capture(textSlot), capture(htmlSlot), any())
        } returns CompletableFuture.completedFuture(true)

        val requests = listOf(
            request("CVE-2026-1", "web-01", "alice"),
            request("CVE-2026-2", "db-02", "bob")
        )

        service.notifyAdminsOfPendingDigest(requests).get()

        assertTrue(subjectSlot.captured.contains("2 new exception requests"))
        for (body in listOf(textSlot.captured, htmlSlot.captured)) {
            assertTrue(body.contains("CVE-2026-1")) { "missing CVE-2026-1 in: $body" }
            assertTrue(body.contains("CVE-2026-2")) { "missing CVE-2026-2 in: $body" }
            assertTrue(body.contains("web-01")) { "missing web-01 in: $body" }
            assertTrue(body.contains("db-02")) { "missing db-02 in: $body" }
        }
    }

    @Test
    fun `single request uses singular subject wording`() {
        every { userRepository.findAll() } returns listOf(user("admin", User.Role.ADMIN))
        val subjectSlot = slot<String>()
        every {
            emailService.sendEmailWithInlineImages(any(), capture(subjectSlot), any(), any(), any())
        } returns CompletableFuture.completedFuture(true)

        service.notifyAdminsOfPendingDigest(listOf(request("CVE-2026-9", "host", "carol"))).get()

        assertTrue(subjectSlot.captured.contains("1 new exception request awaiting"))
    }

    @Test
    fun `returns false and sends nothing when no admins or secchampions exist`() {
        every { userRepository.findAll() } returns listOf(user("regular", User.Role.USER))

        val result = service.notifyAdminsOfPendingDigest(listOf(request("CVE-1", "a", "x"))).get()

        assertFalse(result)
        verify(exactly = 0) { emailService.sendEmailWithInlineImages(any(), any(), any(), any(), any()) }
    }
}
