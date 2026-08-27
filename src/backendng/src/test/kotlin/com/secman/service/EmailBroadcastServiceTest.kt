package com.secman.service

import com.secman.domain.EmailBroadcastJob
import com.secman.domain.EmailBroadcastTargetGroup
import com.secman.domain.User
import com.secman.domain.EolFinding
import com.secman.domain.EolStatus
import com.secman.domain.EolSubjectType
import com.secman.repository.EmailBroadcastJobRepository
import com.secman.repository.EolFindingRepository
import com.secman.repository.UserRepository
import io.micronaut.security.authentication.Authentication
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.Optional
import java.util.concurrent.CompletableFuture

class EmailBroadcastServiceTest {
    private val emailBroadcastJobRepository = mockk<EmailBroadcastJobRepository>()
    private val userRepository = mockk<UserRepository>()
    private val emailService = mockk<EmailService>()
    private val productBroadcastRecipientResolver = mockk<ProductBroadcastRecipientResolver>()
    private val eolBroadcastRecipientResolver = mockk<EolBroadcastRecipientResolver>()
    private val eolFindingRepository = mockk<EolFindingRepository>(relaxed = true)
    private val service = EmailBroadcastService(
        emailBroadcastJobRepository = emailBroadcastJobRepository,
        userRepository = userRepository,
        emailService = emailService,
        productBroadcastRecipientResolver = productBroadcastRecipientResolver,
        eolBroadcastRecipientResolver = eolBroadcastRecipientResolver,
        eolFindingRepository = eolFindingRepository,
        eolFindingTableRenderer = EolFindingTableRenderer()
    )

    @Test
    fun `createJob stores sanitized html`() {
        val jobSlot = slot<EmailBroadcastJob>()
        every { userRepository.findByLastLoginIsNotNull() } returns listOf(activeUser())
        every { emailBroadcastJobRepository.save(capture(jobSlot)) } answers { jobSlot.captured }

        val job = service.createJob(
            subject = "Notice",
            htmlContent = """<p>Hello</p><script>alert(1)</script><img src="x" onerror="alert(2)"><a href="javascript:alert(3)">bad</a>""",
            createdBy = "admin",
            targetGroup = EmailBroadcastTargetGroup.ALL_USERS
        )

        assertThat(job.htmlContent).contains("<p>Hello</p>")
        assertThat(job.htmlContent).doesNotContain("<script")
        assertThat(job.htmlContent).doesNotContain("onerror")
        assertThat(job.htmlContent).doesNotContain("javascript:")
    }

    @Test
    fun `createProductJob stores sanitized html and scoped recipient total`() {
        val authentication = Authentication.build("champion", listOf("SECCHAMPION"), mapOf("userId" to 2L))
        val jobSlot = slot<EmailBroadcastJob>()
        every { productBroadcastRecipientResolver.resolve("Chrome", authentication) } returns listOf(activeUser())
        every { emailBroadcastJobRepository.save(capture(jobSlot)) } answers { jobSlot.captured }

        val job = service.createProductJob(
            subject = "Chrome",
            htmlContent = """<h2>Update</h2><a href="javascript:alert(1)">details</a>""",
            createdBy = "champion",
            productName = " Chrome ",
            authentication = authentication
        )

        assertThat(job.totalRecipients).isEqualTo(1)
        assertThat(job.targetProduct).isEqualTo("Chrome")
        assertThat(job.htmlContent).contains("<h2>Update</h2>")
        assertThat(job.htmlContent).doesNotContain("javascript:")
    }

    @Test
    fun `runProductJobAsync resolves product recipients with authentication`() {
        val authentication = Authentication.build("champion", listOf("SECCHAMPION"), mapOf("userId" to 2L))
        val job = EmailBroadcastJob(
            id = 42,
            subject = "Chrome",
            htmlContent = "<p>Update</p>",
            totalRecipients = 1,
            createdBy = "champion",
            targetGroup = EmailBroadcastTargetGroup.PRODUCT_USERS,
            targetProduct = "Chrome"
        )
        every { emailBroadcastJobRepository.findById(42) } returns Optional.of(job)
        every { emailBroadcastJobRepository.update(any<EmailBroadcastJob>()) } answers { firstArg() }
        every { productBroadcastRecipientResolver.resolve("Chrome", authentication) } returns listOf(activeUser())
        every {
            emailService.sendEmailWithInlineImages(any(), any(), any(), any(), any())
        } returns CompletableFuture.completedFuture(true)

        service.runProductJobAsync(42, authentication).get()

        io.mockk.verify(exactly = 1) {
            productBroadcastRecipientResolver.resolve("Chrome", authentication)
        }
    }

    @Test
    fun `createEolProductJob stores sanitized html and scoped recipient total`() {
        val authentication = Authentication.build("champion", listOf("SECCHAMPION"), mapOf("userId" to 2L))
        val jobSlot = slot<EmailBroadcastJob>()
        every { eolBroadcastRecipientResolver.resolve("Internet Explorer", authentication) } returns
            listOf(EolBroadcastRecipient(activeUser(), setOf(701L)))
        every { emailBroadcastJobRepository.save(capture(jobSlot)) } answers { jobSlot.captured }

        val job = service.createEolProductJob(
            subject = "EOL notice",
            htmlContent = """<h2>Update</h2><a href="javascript:alert(1)">details</a>""",
            createdBy = "champion",
            productName = " Internet Explorer ",
            authentication = authentication
        )

        assertThat(job.totalRecipients).isEqualTo(1)
        assertThat(job.targetGroup).isEqualTo(EmailBroadcastTargetGroup.EOL_PRODUCT_USERS)
        assertThat(job.targetProduct).isEqualTo("Internet Explorer")
        assertThat(job.htmlContent).contains("<h2>Update</h2>")
        assertThat(job.htmlContent).doesNotContain("javascript:")
    }

    @Test
    fun `runEolProductJobAsync resolves EOL product recipients with authentication`() {
        val authentication = Authentication.build("champion", listOf("SECCHAMPION"), mapOf("userId" to 2L))
        val job = EmailBroadcastJob(
            id = 43,
            subject = "EOL notice",
            htmlContent = "<p>Update</p>",
            totalRecipients = 1,
            createdBy = "champion",
            targetGroup = EmailBroadcastTargetGroup.EOL_PRODUCT_USERS,
            targetProduct = "Internet Explorer"
        )
        every { emailBroadcastJobRepository.findById(43) } returns Optional.of(job)
        every { emailBroadcastJobRepository.update(any<EmailBroadcastJob>()) } answers { firstArg() }
        every { eolBroadcastRecipientResolver.resolve("Internet Explorer", authentication) } returns
            listOf(EolBroadcastRecipient(activeUser(), setOf(701L)))
        every {
            emailService.sendEmailWithInlineImages(any(), any(), any(), any(), any(), any())
        } returns CompletableFuture.completedFuture(true)

        service.runEolProductJobAsync(43, authentication).get()

        io.mockk.verify(exactly = 1) {
            eolBroadcastRecipientResolver.resolve("Internet Explorer", authentication)
        }
    }

    /**
     * The body an EOL recipient receives must list the affected systems that linked
     * *them* to the product and no others — the scoping assertion lives in
     * [EolBroadcastRecipientResolverTest]; this asserts the scoped rows actually
     * reach the message rather than being resolved and discarded.
     */
    @Test
    fun `runEolProductJobAsync appends the affected-systems table scoped to the recipient`() {
        val authentication = Authentication.build("champion", listOf("SECCHAMPION"), mapOf("userId" to 2L))
        val job = EmailBroadcastJob(
            id = 45,
            subject = "EOL notice",
            htmlContent = "<p>Update</p>",
            totalRecipients = 1,
            createdBy = "champion",
            targetGroup = EmailBroadcastTargetGroup.EOL_PRODUCT_USERS,
            targetProduct = "Internet Explorer"
        )
        val htmlSlot = slot<String>()
        val textSlot = slot<String>()
        every { emailBroadcastJobRepository.findById(45) } returns Optional.of(job)
        every { emailBroadcastJobRepository.update(any<EmailBroadcastJob>()) } answers { firstArg() }
        every { eolBroadcastRecipientResolver.resolve("Internet Explorer", authentication) } returns
            listOf(EolBroadcastRecipient(activeUser(), setOf(701L)))
        every {
            eolFindingRepository.findByComponentNameForAssets("Internet Explorer", setOf(701L), any())
        } returns listOf(eolFinding())
        every {
            eolFindingRepository.countByComponentNameForAssets("Internet Explorer", setOf(701L))
        } returns 1L
        every {
            emailService.sendEmailWithInlineImages(any(), any(), capture(textSlot), capture(htmlSlot), any(), any())
        } returns CompletableFuture.completedFuture(true)

        service.runEolProductJobAsync(45, authentication).get()

        assertThat(htmlSlot.captured)
            .contains("<p>Update</p>")
            .contains("Affected systems")
            .contains("web-01")
            .contains("123456789012")
            .contains("i-0abc123")
        assertThat(textSlot.captured).contains("web-01").contains("i-0abc123")
    }

    @Test
    fun `a non-EOL broadcast carries no affected-systems table`() {
        val job = EmailBroadcastJob(
            id = 46,
            subject = "Notice",
            htmlContent = "<p>Update</p>",
            totalRecipients = 1,
            createdBy = "admin",
            targetGroup = EmailBroadcastTargetGroup.ALL_USERS
        )
        val htmlSlot = slot<String>()
        every { emailBroadcastJobRepository.findById(46) } returns Optional.of(job)
        every { emailBroadcastJobRepository.update(any<EmailBroadcastJob>()) } answers { firstArg() }
        every { userRepository.findByLastLoginIsNotNull() } returns listOf(activeUser())
        every {
            emailService.sendEmailWithInlineImages(any(), any(), any(), capture(htmlSlot), any(), any())
        } returns CompletableFuture.completedFuture(true)

        service.runJobAsync(46).get()

        assertThat(htmlSlot.captured).contains("<p>Update</p>").doesNotContain("Affected systems")
    }

    private fun eolFinding() = EolFinding(
        id = 1L,
        subjectType = EolSubjectType.ASSET_PRODUCT,
        assetId = 701L,
        assetName = "web-01",
        cloudAccountId = "123456789012",
        cloudInstanceId = "i-0abc123",
        adDomain = "corp.example.com",
        assetOwner = "owner",
        componentName = "Internet Explorer",
        componentVersion = "11",
        eolCycle = "11",
        eolDate = java.time.LocalDate.of(2022, 6, 15),
        status = EolStatus.EOL
    )

    @Test
    fun `createEolProductJob serializes manually-added cc addresses`() {
        val authentication = Authentication.build("champion", listOf("SECCHAMPION"), mapOf("userId" to 2L))
        val jobSlot = slot<EmailBroadcastJob>()
        every { eolBroadcastRecipientResolver.resolve("Internet Explorer", authentication) } returns
            listOf(EolBroadcastRecipient(activeUser(), setOf(701L)))
        every { emailBroadcastJobRepository.save(capture(jobSlot)) } answers { jobSlot.captured }

        val job = service.createEolProductJob(
            subject = "EOL notice",
            htmlContent = "<p>Update</p>",
            createdBy = "champion",
            productName = "Internet Explorer",
            authentication = authentication,
            ccRecipients = listOf(" manager@example.com ", "manager@example.com", "second@example.com")
        )

        assertThat(job.ccRecipients).isEqualTo("manager@example.com,second@example.com")
    }

    @Test
    fun `runEolProductJobAsync cc's manually-added addresses on every message`() {
        val authentication = Authentication.build("champion", listOf("SECCHAMPION"), mapOf("userId" to 2L))
        val job = EmailBroadcastJob(
            id = 44,
            subject = "EOL notice",
            htmlContent = "<p>Update</p>",
            totalRecipients = 1,
            createdBy = "champion",
            targetGroup = EmailBroadcastTargetGroup.EOL_PRODUCT_USERS,
            targetProduct = "Internet Explorer",
            ccRecipients = "manager@example.com,second@example.com"
        )
        val ccSlot = slot<List<String>>()
        every { emailBroadcastJobRepository.findById(44) } returns Optional.of(job)
        every { emailBroadcastJobRepository.update(any<EmailBroadcastJob>()) } answers { firstArg() }
        every { eolBroadcastRecipientResolver.resolve("Internet Explorer", authentication) } returns
            listOf(EolBroadcastRecipient(activeUser(), setOf(701L)))
        every {
            emailService.sendEmailWithInlineImages(any(), any(), any(), any(), any(), capture(ccSlot))
        } returns CompletableFuture.completedFuture(true)

        service.runEolProductJobAsync(44, authentication).get()

        assertThat(ccSlot.captured).containsExactly("manager@example.com", "second@example.com")
    }

    private fun activeUser(): User =
        User(
            id = 1,
            username = "user",
            email = "user@example.com",
            passwordHash = "x",
            lastLogin = Instant.now()
        )
}
