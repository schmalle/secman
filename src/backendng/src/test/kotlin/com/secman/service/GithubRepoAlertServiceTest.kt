package com.secman.service

import com.secman.domain.ExecutionStatus
import com.secman.domain.GithubRepoAlertException
import com.secman.domain.GithubRepoFindingSnapshot
import com.secman.domain.GithubRepository
import com.secman.repository.GithubRepoAlertExceptionRepository
import com.secman.repository.GithubRepoFindingSnapshotRepository
import com.secman.repository.GithubRepositoryRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Optional
import java.util.concurrent.CompletableFuture

class GithubRepoAlertServiceTest {

    private lateinit var repoRepository: GithubRepositoryRepository
    private lateinit var snapshotRepository: GithubRepoFindingSnapshotRepository
    private lateinit var exceptionRepository: GithubRepoAlertExceptionRepository
    private lateinit var emailService: EmailService
    private lateinit var service: GithubRepoAlertService

    private val now: Instant = Instant.now()

    @BeforeEach
    fun setUp() {
        repoRepository = mockk()
        snapshotRepository = mockk()
        exceptionRepository = mockk()
        emailService = mockk()
        service = GithubRepoAlertService(repoRepository, snapshotRepository, exceptionRepository, emailService)

        every { exceptionRepository.findByGithubRepositoryId(any()) } returns emptyList()
        every { emailService.sendEmailWithInlineImages(any(), any(), any(), any(), any()) } returns
            CompletableFuture.completedFuture(true)
    }

    private fun repo(
        id: Long,
        fullName: String = "org/repo$id",
        critical: Int = 0,
        high: Int = 0,
        ownerEmail: String? = "owner@example.com"
    ) = GithubRepository(
        id = id,
        githubRepoId = id * 100,
        name = fullName.substringAfter('/'),
        owner = fullName.substringBefore('/'),
        fullName = fullName,
        ownerEmail = ownerEmail,
        criticalCount = critical,
        highCount = high
    )

    private fun baseline(repoId: Long, critical: Int, high: Int, daysAgo: Long = 35) =
        GithubRepoFindingSnapshot(
            id = repoId * 10,
            githubRepositoryId = repoId,
            snapshotAt = now.minus(daysAgo, ChronoUnit.DAYS),
            criticalCount = critical,
            highCount = high
        )

    private fun stubBaseline(repoId: Long, snapshot: GithubRepoFindingSnapshot?) {
        every {
            snapshotRepository.findFirstByGithubRepositoryIdAndSnapshotAtLessThanEqualOrderBySnapshotAtDesc(repoId, any())
        } returns Optional.ofNullable(snapshot)
    }

    @Test
    fun `alerts when count has not decreased`() {
        val r = repo(1, critical = 2, high = 3)
        every { repoRepository.findAll() } returns listOf(r)
        stubBaseline(1, baseline(1, critical = 2, high = 3))

        val result = service.sendGithubRepoAlerts(dryRun = false)

        assertThat(result.reposAlerted).isEqualTo(1)
        assertThat(result.status).isEqualTo(ExecutionStatus.SUCCESS)
        assertThat(result.emailsSent).isEqualTo(1)
        assertThat(result.recipients).containsExactly("owner@example.com")
    }

    @Test
    fun `alerts when count increased`() {
        val r = repo(1, critical = 5, high = 1)
        every { repoRepository.findAll() } returns listOf(r)
        stubBaseline(1, baseline(1, critical = 1, high = 1))

        val result = service.sendGithubRepoAlerts(dryRun = true)

        assertThat(result.reposAlerted).isEqualTo(1)
    }

    @Test
    fun `does not alert when count decreased`() {
        val r = repo(1, critical = 1, high = 1)
        every { repoRepository.findAll() } returns listOf(r)
        stubBaseline(1, baseline(1, critical = 3, high = 3))

        val result = service.sendGithubRepoAlerts(dryRun = false)

        assertThat(result.reposAlerted).isEqualTo(0)
        verify(exactly = 0) { emailService.sendEmailWithInlineImages(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `does not alert when current count is zero`() {
        val r = repo(1, critical = 0, high = 0)
        every { repoRepository.findAll() } returns listOf(r)

        val result = service.sendGithubRepoAlerts(dryRun = false)

        assertThat(result.reposAlerted).isEqualTo(0)
        assertThat(result.reposSkippedInsufficientHistory).isEmpty()
    }

    @Test
    fun `skips and reports repos without a 30-day-old snapshot`() {
        val r = repo(1, critical = 4, high = 0)
        every { repoRepository.findAll() } returns listOf(r)
        stubBaseline(1, null)

        val result = service.sendGithubRepoAlerts(dryRun = false)

        assertThat(result.reposAlerted).isEqualTo(0)
        assertThat(result.reposSkippedInsufficientHistory).containsExactly("org/repo1")
    }

    @Test
    fun `skips repos with an active exception`() {
        val r = repo(1, critical = 4, high = 0)
        every { repoRepository.findAll() } returns listOf(r)
        every { exceptionRepository.findByGithubRepositoryId(1) } returns listOf(
            GithubRepoAlertException(id = 1, githubRepositoryId = 1, reason = "accepted risk", expirationDate = null)
        )

        val result = service.sendGithubRepoAlerts(dryRun = false)

        assertThat(result.reposAlerted).isEqualTo(0)
        assertThat(result.reposExcepted).containsExactly("org/repo1")
        verify(exactly = 0) { emailService.sendEmailWithInlineImages(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `expired exception does not suppress the alert`() {
        val r = repo(1, critical = 4, high = 0)
        every { repoRepository.findAll() } returns listOf(r)
        every { exceptionRepository.findByGithubRepositoryId(1) } returns listOf(
            GithubRepoAlertException(
                id = 1, githubRepositoryId = 1, reason = "expired",
                expirationDate = now.minus(1, ChronoUnit.DAYS)
            )
        )
        stubBaseline(1, baseline(1, critical = 4, high = 0))

        val result = service.sendGithubRepoAlerts(dryRun = false)

        assertThat(result.reposExcepted).isEmpty()
        assertThat(result.reposAlerted).isEqualTo(1)
    }

    @Test
    fun `repo without ownerEmail is reported as unmapped and gets no email`() {
        val r = repo(1, critical = 4, high = 0, ownerEmail = null)
        every { repoRepository.findAll() } returns listOf(r)
        stubBaseline(1, baseline(1, critical = 4, high = 0))

        val result = service.sendGithubRepoAlerts(dryRun = false)

        assertThat(result.reposAlerted).isEqualTo(1)
        assertThat(result.unmappedRepos).containsExactly("org/repo1")
        verify(exactly = 0) { emailService.sendEmailWithInlineImages(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `dry run computes results without sending`() {
        val r = repo(1, critical = 2, high = 2)
        every { repoRepository.findAll() } returns listOf(r)
        stubBaseline(1, baseline(1, critical = 2, high = 2))

        val result = service.sendGithubRepoAlerts(dryRun = true)

        assertThat(result.status).isEqualTo(ExecutionStatus.DRY_RUN)
        assertThat(result.reposAlerted).isEqualTo(1)
        assertThat(result.emailsSent).isEqualTo(0)
        verify(exactly = 0) { emailService.sendEmailWithInlineImages(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `one email per owner covering all their repos`() {
        val r1 = repo(1, fullName = "org/a", critical = 1, high = 0)
        val r2 = repo(2, fullName = "org/b", critical = 0, high = 2)
        every { repoRepository.findAll() } returns listOf(r1, r2)
        stubBaseline(1, baseline(1, critical = 1, high = 0))
        stubBaseline(2, baseline(2, critical = 0, high = 2))

        val result = service.sendGithubRepoAlerts(dryRun = false)

        assertThat(result.reposAlerted).isEqualTo(2)
        assertThat(result.emailsSent).isEqualTo(1)
        verify(exactly = 1) { emailService.sendEmailWithInlineImages(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `email failure yields FAILURE status and failedRecipients`() {
        val r = repo(1, critical = 2, high = 2)
        every { repoRepository.findAll() } returns listOf(r)
        stubBaseline(1, baseline(1, critical = 2, high = 2))
        every { emailService.sendEmailWithInlineImages(any(), any(), any(), any(), any()) } returns
            CompletableFuture.completedFuture(false)

        val result = service.sendGithubRepoAlerts(dryRun = false)

        assertThat(result.status).isEqualTo(ExecutionStatus.FAILURE)
        assertThat(result.failedRecipients).containsExactly("owner@example.com")
    }

    @Test
    fun `partial failure when some emails succeed`() {
        val r1 = repo(1, fullName = "org/a", critical = 1, high = 0, ownerEmail = "good@example.com")
        val r2 = repo(2, fullName = "org/b", critical = 1, high = 0, ownerEmail = "bad@example.com")
        every { repoRepository.findAll() } returns listOf(r1, r2)
        stubBaseline(1, baseline(1, critical = 1, high = 0))
        stubBaseline(2, baseline(2, critical = 1, high = 0))
        every { emailService.sendEmailWithInlineImages("good@example.com", any(), any(), any(), any()) } returns
            CompletableFuture.completedFuture(true)
        every { emailService.sendEmailWithInlineImages("bad@example.com", any(), any(), any(), any()) } returns
            CompletableFuture.completedFuture(false)

        val result = service.sendGithubRepoAlerts(dryRun = false)

        assertThat(result.status).isEqualTo(ExecutionStatus.PARTIAL_FAILURE)
        assertThat(result.recipients).containsExactly("good@example.com")
        assertThat(result.failedRecipients).containsExactly("bad@example.com")
    }
}
