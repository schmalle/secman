package com.secman.service

import com.secman.config.AppConfig
import com.secman.domain.Asset
import com.secman.domain.ExceptionRequestStatus
import com.secman.domain.User
import com.secman.domain.VulnerabilityException
import com.secman.domain.VulnerabilityExceptionRequest
import com.secman.repository.AssetRepository
import com.secman.repository.UserRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.hibernate.LazyInitializationException
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.util.Optional
import java.util.concurrent.CompletableFuture

class ExceptionRequestNotificationServiceTest {

    private val emailService = mockk<EmailService>(relaxed = true)
    private val userRepository = mockk<UserRepository>(relaxed = true)
    private val assetRepository = mockk<AssetRepository>(relaxed = true)
    private val service = ExceptionRequestNotificationService(
        emailService = emailService,
        userRepository = userRepository,
        assetRepository = assetRepository,
        appConfig = AppConfig()
    )

    private val secChampion = User(
        id = 1,
        username = "champion",
        email = "champion@example.com",
        passwordHash = "x",
        roles = mutableSetOf(User.Role.SECCHAMPION)
    )

    private val originatingAsset = Asset(
        id = 42,
        name = "ANTMESVIS01",
        type = "SERVER",
        owner = "someone",
        ip = "10.23.28.37"
    )

    private fun baseRequest(
        scope: VulnerabilityException.Scope,
        scopeValue: String? = null,
        assetId: Long? = 42
    ) = VulnerabilityExceptionRequest(
        id = 1,
        requestedByUsername = "demo",
        subject = VulnerabilityException.Subject.CVE,
        scope = scope,
        scopeValue = scopeValue,
        reason = "x".repeat(50),
        expirationDate = LocalDateTime.now().plusDays(30),
        status = ExceptionRequestStatus.PENDING,
        cveId = "CVE-2026-12448",
        assetId = assetId
    )

    private fun captureNewRequestHtml(request: VulnerabilityExceptionRequest): String {
        every { userRepository.findAll() } returns listOf(secChampion)
        every { assetRepository.findById(42L) } returns Optional.of(originatingAsset)
        val subjectSlot = slot<String>()
        val htmlSlot = slot<String>()
        val textSlot = slot<String>()
        every {
            emailService.sendEmailWithInlineImages(
                any(), capture(subjectSlot), capture(textSlot), capture(htmlSlot), any()
            )
        } returns CompletableFuture.completedFuture(true)

        service.notifyAdminsOfNewRequest(request).get()

        assertThat(textSlot.captured).isNotEmpty()
        return "${subjectSlot.captured}\n${htmlSlot.captured}"
    }

    @Test
    fun `OS scope request describes the OS class, not the originating asset`() {
        val request = baseRequest(VulnerabilityException.Scope.OS, scopeValue = "Windows Server 2016")

        val combined = captureNewRequestHtml(request)

        assertThat(combined).contains("OS: Windows Server 2016")
        assertThat(combined).doesNotContain("CVE-2026-12448 on ANTMESVIS01")
        assertThat(combined).contains("Originating Asset")
        assertThat(combined).contains("ANTMESVIS01")
    }

    @Test
    fun `GLOBAL scope request describes all assets, not the originating asset`() {
        val request = baseRequest(VulnerabilityException.Scope.GLOBAL)

        val combined = captureNewRequestHtml(request)

        assertThat(combined).contains("All Assets (Global)")
        assertThat(combined).doesNotContain("CVE-2026-12448 on ANTMESVIS01")
        assertThat(combined).contains("Originating Asset")
        assertThat(combined).contains("ANTMESVIS01")
    }

    @Test
    fun `ASSET scope request shows the asset name with no Originating Asset row`() {
        val request = baseRequest(VulnerabilityException.Scope.ASSET)

        val combined = captureNewRequestHtml(request)

        assertThat(combined).contains("CVE-2026-12448 on ANTMESVIS01")
        assertThat(combined).doesNotContain("Originating Asset")
    }

    @Test
    fun `approval email for OS scope request reflects the OS class`() {
        val request = baseRequest(VulnerabilityException.Scope.OS, scopeValue = "Windows Server 2016")
        every { assetRepository.findById(42L) } returns Optional.of(originatingAsset)
        every { userRepository.findByUsername("demo") } returns Optional.of(requester)
        every { emailService.sendHtmlEmail(any(), any(), any()) } returns CompletableFuture.completedFuture(true)

        val subjectSlot = slot<String>()
        every { emailService.sendHtmlEmail(any(), capture(subjectSlot), any()) } returns
            CompletableFuture.completedFuture(true)

        service.notifyRequesterOfApproval(request).get()

        assertThat(subjectSlot.captured).contains("OS: Windows Server 2016")
        assertThat(subjectSlot.captured).doesNotContain("on ANTMESVIS01")
    }

    /**
     * A `requestedByUser` association that is still an uninitialized Hibernate proxy
     * after the session closed. Both notification paths run from an AFTER_COMMIT
     * listener, so this is the state they actually see in production.
     */
    private fun detachedRequesterProxy(): User = mockk<User> {
        every { email } throws LazyInitializationException(
            "Could not initialize proxy [com.secman.domain.User#3852] - no session"
        )
    }

    private val requester = User(
        id = 3852,
        username = "demo",
        email = "demo@example.com",
        passwordHash = "x"
    )

    @Test
    fun `approval notification survives a detached requester proxy`() {
        val request = baseRequest(VulnerabilityException.Scope.ASSET).apply {
            requestedByUser = detachedRequesterProxy()
        }
        every { assetRepository.findById(42L) } returns Optional.of(originatingAsset)
        every { userRepository.findByUsername("demo") } returns Optional.of(requester)

        val toSlot = slot<String>()
        every { emailService.sendHtmlEmail(capture(toSlot), any(), any()) } returns
            CompletableFuture.completedFuture(true)

        assertThat(service.notifyRequesterOfApproval(request).get()).isTrue()
        assertThat(toSlot.captured).isEqualTo("demo@example.com")
    }

    @Test
    fun `rejection notification survives a detached requester proxy`() {
        val request = baseRequest(VulnerabilityException.Scope.ASSET).apply {
            requestedByUser = detachedRequesterProxy()
            reviewComment = "Not acceptable"
        }
        every { assetRepository.findById(42L) } returns Optional.of(originatingAsset)
        every { userRepository.findByUsername("demo") } returns Optional.of(requester)

        val toSlot = slot<String>()
        every { emailService.sendHtmlEmail(capture(toSlot), any(), any()) } returns
            CompletableFuture.completedFuture(true)

        assertThat(service.notifyRequesterOfRejection(request).get()).isTrue()
        assertThat(toSlot.captured).isEqualTo("demo@example.com")
    }

    @Test
    fun `notification is skipped when the requester no longer exists`() {
        val request = baseRequest(VulnerabilityException.Scope.ASSET).apply {
            requestedByUser = detachedRequesterProxy()
        }
        every { assetRepository.findById(42L) } returns Optional.of(originatingAsset)
        every { userRepository.findByUsername("demo") } returns Optional.empty()

        assertThat(service.notifyRequesterOfApproval(request).get()).isFalse()
        verify(exactly = 0) { emailService.sendHtmlEmail(any(), any(), any()) }
    }

    @Test
    fun `new-request email HTML-escapes attacker-controlled username and reason`() {
        val payload = "<script>alert(document.cookie)</script>"
        val request = VulnerabilityExceptionRequest(
            id = 1,
            requestedByUsername = "demo$payload",
            subject = VulnerabilityException.Subject.CVE,
            scope = VulnerabilityException.Scope.ASSET,
            reason = payload + "x".repeat(50),
            expirationDate = LocalDateTime.now().plusDays(30),
            status = ExceptionRequestStatus.PENDING,
            cveId = "CVE-2026-12448",
            assetId = 42
        )

        val combined = captureNewRequestHtml(request)

        assertThat(combined).doesNotContain("<script>")
        assertThat(combined).contains("&lt;script&gt;")
    }

    @Test
    fun `approval email HTML-escapes an attacker-controlled reviewer comment`() {
        val payload = "<img src=x onerror=alert(1)>"
        val request = baseRequest(VulnerabilityException.Scope.ASSET).apply {
            reviewComment = payload
        }
        every { assetRepository.findById(42L) } returns Optional.of(originatingAsset)
        every { userRepository.findByUsername("demo") } returns Optional.of(requester)
        val htmlSlot = slot<String>()
        every { emailService.sendHtmlEmail(any(), any(), capture(htmlSlot)) } returns
            CompletableFuture.completedFuture(true)

        service.notifyRequesterOfApproval(request).get()

        assertThat(htmlSlot.captured).doesNotContain("<img src=x onerror=alert(1)>")
        assertThat(htmlSlot.captured).contains("&lt;img src=x onerror=alert(1)&gt;")
    }

    @Test
    fun `rejection email HTML-escapes an attacker-controlled reviewer comment`() {
        val payload = "<img src=x onerror=alert(1)>"
        val request = baseRequest(VulnerabilityException.Scope.ASSET).apply {
            reviewComment = payload
        }
        every { assetRepository.findById(42L) } returns Optional.of(originatingAsset)
        every { userRepository.findByUsername("demo") } returns Optional.of(requester)
        val htmlSlot = slot<String>()
        every { emailService.sendHtmlEmail(any(), any(), capture(htmlSlot)) } returns
            CompletableFuture.completedFuture(true)

        service.notifyRequesterOfRejection(request).get()

        assertThat(htmlSlot.captured).doesNotContain("<img src=x onerror=alert(1)>")
        assertThat(htmlSlot.captured).contains("&lt;img src=x onerror=alert(1)&gt;")
    }
}
