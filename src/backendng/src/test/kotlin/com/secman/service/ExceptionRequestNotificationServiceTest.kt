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
import org.assertj.core.api.Assertions.assertThat
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
        val request = baseRequest(VulnerabilityException.Scope.OS, scopeValue = "Windows Server 2016").apply {
            requestedByUser = User(
                id = 2,
                username = "demo",
                email = "demo@example.com",
                passwordHash = "x"
            )
        }
        every { assetRepository.findById(42L) } returns Optional.of(originatingAsset)
        every { emailService.sendHtmlEmail(any(), any(), any()) } returns CompletableFuture.completedFuture(true)

        val subjectSlot = slot<String>()
        every { emailService.sendHtmlEmail(any(), capture(subjectSlot), any()) } returns
            CompletableFuture.completedFuture(true)

        service.notifyRequesterOfApproval(request).get()

        assertThat(subjectSlot.captured).contains("OS: Windows Server 2016")
        assertThat(subjectSlot.captured).doesNotContain("on ANTMESVIS01")
    }
}
