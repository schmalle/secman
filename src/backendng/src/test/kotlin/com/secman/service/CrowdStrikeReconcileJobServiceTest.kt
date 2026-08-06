package com.secman.service

import com.secman.domain.CrowdStrikeReconcileJob
import com.secman.domain.ReconcileJobStatus
import com.secman.dto.ReconcileStaleResult
import com.secman.dto.ReconcileStaleVulnerabilitiesRequest
import com.secman.repository.CrowdStrikeReconcileJobRepository
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.util.Optional
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit

/**
 * Unit tests for the async reconcile job lifecycle. Uses a same-thread executor so
 * the background sweep runs synchronously inside startReconcile, making the full
 * PENDING -> RUNNING -> COMPLETED/FAILED lifecycle assertable without waiting.
 */
class CrowdStrikeReconcileJobServiceTest {

    private val jobs = mutableMapOf<String, CrowdStrikeReconcileJob>()

    private lateinit var jobRepository: CrowdStrikeReconcileJobRepository
    private lateinit var importService: CrowdStrikeVulnerabilityImportService
    private lateinit var service: CrowdStrikeReconcileJobService

    @BeforeEach
    fun setUp() {
        jobs.clear()
        jobRepository = mockk()
        every { jobRepository.save(any()) } answers {
            val job = firstArg<CrowdStrikeReconcileJob>()
            jobs[job.id] = job
            job
        }
        every { jobRepository.update(any<CrowdStrikeReconcileJob>()) } answers {
            val job = firstArg<CrowdStrikeReconcileJob>()
            jobs[job.id] = job
            job
        }
        every { jobRepository.findById(any()) } answers {
            Optional.ofNullable(jobs[firstArg<String>()])
        }
        every { jobRepository.findByIdAndUsername(any(), any()) } answers {
            Optional.ofNullable(jobs[firstArg<String>()]?.takeIf { it.username == secondArg<String>() })
        }
        every { jobRepository.findByStatusIn(any()) } answers {
            val statuses = firstArg<List<ReconcileJobStatus>>()
            jobs.values.filter { it.status in statuses }
        }

        importService = mockk()
        service = CrowdStrikeReconcileJobService(
            jobRepository = jobRepository,
            importService = importService,
            executorService = DirectExecutorService(),
            entityManager = mockk(relaxed = true)
        )
        injectSelfProvider()
    }

    private fun injectSelfProvider() {
        val provider = mockk<jakarta.inject.Provider<CrowdStrikeReconcileJobService>>()
        every { provider.get() } returns service
        val field = CrowdStrikeReconcileJobService::class.java.getDeclaredField("selfProvider")
        field.isAccessible = true
        field.set(service, provider)
    }

    private fun request() = ReconcileStaleVulnerabilitiesRequest(
        importStartedAt = LocalDateTime.now().minusHours(1),
        severities = listOf("CRITICAL", "HIGH"),
        queriedHosts = null
    )

    @Test
    fun `successful sweep completes job with result fields`() {
        every { importService.reconcileStaleCrowdStrikeImports(any(), any(), any()) } returns
            ReconcileStaleResult(rowsDeleted = 42)

        val started = service.startReconcile("adminuser", request())

        assertThat(started.jobId).isNotBlank()
        val job = jobs[started.jobId]!!
        assertThat(job.status).isEqualTo(ReconcileJobStatus.COMPLETED)
        assertThat(job.rowsDeleted).isEqualTo(42)
        assertThat(job.severities).isEqualTo("CRITICAL,HIGH")
        assertThat(job.aborted).isFalse()
        assertThat(job.completedAt).isNotNull()
    }

    @Test
    fun `aborted sweep is COMPLETED with aborted flag, not FAILED`() {
        every { importService.reconcileStaleCrowdStrikeImports(any(), any(), any()) } returns
            ReconcileStaleResult(rowsDeleted = 0, aborted = true, abortReason = "suspected empty run")

        val started = service.startReconcile("adminuser", request())

        val status = service.getJobStatus(started.jobId, "adminuser")!!
        assertThat(status.status).isEqualTo("COMPLETED")
        assertThat(status.result).isNotNull()
        assertThat(status.result!!.aborted).isTrue()
        assertThat(status.result!!.abortReason).isEqualTo("suspected empty run")
    }

    @Test
    fun `sweep exception marks job FAILED with message`() {
        every { importService.reconcileStaleCrowdStrikeImports(any(), any(), any()) } throws
            RuntimeException("db connection lost")

        val started = service.startReconcile("adminuser", request())

        val job = jobs[started.jobId]!!
        assertThat(job.status).isEqualTo(ReconcileJobStatus.FAILED)
        assertThat(job.errorMessage).isEqualTo("db connection lost")
        val status = service.getJobStatus(started.jobId, "adminuser")!!
        assertThat(status.status).isEqualTo("FAILED")
        assertThat(status.result).isNull()
    }

    @Test
    fun `running job blocks a second start with conflict carrying its id`() {
        val running = CrowdStrikeReconcileJob(id = "job-1", username = "adminuser")
            .apply { status = ReconcileJobStatus.RUNNING }
        jobs[running.id] = running

        assertThatThrownBy { service.startReconcile("adminuser", request()) }
            .isInstanceOf(ReconcileJobConflictException::class.java)
            .hasFieldOrPropertyWithValue("existingJobId", "job-1")
    }

    @Test
    fun `stuck job older than timeout is auto-failed and does not block a new start`() {
        val stuck = CrowdStrikeReconcileJob(
            id = "job-stuck",
            username = "adminuser",
            createdAt = LocalDateTime.now().minusMinutes(CrowdStrikeReconcileJobService.STUCK_JOB_TIMEOUT_MIN + 1)
        ).apply { status = ReconcileJobStatus.RUNNING }
        jobs[stuck.id] = stuck
        every { importService.reconcileStaleCrowdStrikeImports(any(), any(), any()) } returns
            ReconcileStaleResult(rowsDeleted = 0)

        val started = service.startReconcile("adminuser", request())

        assertThat(jobs["job-stuck"]!!.status).isEqualTo(ReconcileJobStatus.FAILED)
        assertThat(jobs["job-stuck"]!!.errorMessage).contains("Auto-failed")
        assertThat(jobs[started.jobId]!!.status).isEqualTo(ReconcileJobStatus.COMPLETED)
    }

    @Test
    fun `getJobStatus returns null for another user's job`() {
        val job = CrowdStrikeReconcileJob(id = "job-1", username = "adminuser")
        jobs[job.id] = job

        assertThat(service.getJobStatus("job-1", "someone-else")).isNull()
        assertThat(service.getJobStatus("job-1", "adminuser")).isNotNull()
    }

    /** Runs submitted tasks synchronously on the caller's thread. */
    private class DirectExecutorService : AbstractExecutorService() {
        override fun execute(command: Runnable) = command.run()
        override fun shutdown() {}
        override fun shutdownNow(): MutableList<Runnable> = mutableListOf()
        override fun isShutdown() = false
        override fun isTerminated() = false
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = true
    }
}
