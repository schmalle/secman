package com.secman.service

import com.secman.domain.EmailBroadcastJob
import com.secman.domain.EmailBroadcastTargetGroup
import com.secman.domain.User
import com.secman.repository.EmailBroadcastJobRepository
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
    private val service = EmailBroadcastService(
        emailBroadcastJobRepository = emailBroadcastJobRepository,
        userRepository = userRepository,
        emailService = emailService,
        productBroadcastRecipientResolver = productBroadcastRecipientResolver,
        eolBroadcastRecipientResolver = eolBroadcastRecipientResolver
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
        every { eolBroadcastRecipientResolver.resolve("Internet Explorer", authentication) } returns listOf(activeUser())
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
        every { eolBroadcastRecipientResolver.resolve("Internet Explorer", authentication) } returns listOf(activeUser())
        every {
            emailService.sendEmailWithInlineImages(any(), any(), any(), any(), any())
        } returns CompletableFuture.completedFuture(true)

        service.runEolProductJobAsync(43, authentication).get()

        io.mockk.verify(exactly = 1) {
            eolBroadcastRecipientResolver.resolve("Internet Explorer", authentication)
        }
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
