package com.secman.service

import com.secman.dto.BulkUserMappingEntry
import com.secman.dto.BulkUserMappingRequest
import com.secman.dto.BulkUserMappingResponse
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit

/**
 * The async wrapper must hand back exactly what the synchronous import returns, refuse a
 * second import while one runs, and never show one admin's job to another.
 */
class UserMappingBulkImportJobServiceTest {

    /** Holds submitted work until the test runs it, so PENDING is observable. */
    private class DeferredExecutor : AbstractExecutorService() {
        val queued = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { queued.addLast(command) }
        fun runAll() { while (queued.isNotEmpty()) queued.removeFirst().run() }
        override fun shutdown() {}
        override fun shutdownNow(): MutableList<Runnable> = mutableListOf()
        override fun isShutdown() = false
        override fun isTerminated() = false
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = true
    }

    private val bulkImportService = mockk<UserMappingBulkImportService>()
    private val executor = DeferredExecutor()
    private val service = UserMappingBulkImportJobService(bulkImportService, executor)

    private val request = BulkUserMappingRequest(
        mappings = listOf(BulkUserMappingEntry(email = "owner@example.com", awsAccountId = "111111111111"))
    )
    private val result = BulkUserMappingResponse(
        totalProcessed = 1, created = 0, createdPending = 0, skipped = 1, errors = emptyList()
    )

    @Test
    fun `completed job returns the synchronous import result`() {
        every { bulkImportService.execute(request, 7L, "Bulk import API") } returns result

        val started = service.start(request, 7L, "admin")
        assertThat(started.status).isEqualTo("PENDING")
        assertThat(service.getStatus(started.jobId, "admin")!!.result).isNull()

        executor.runAll()

        val status = service.getStatus(started.jobId, "admin")!!
        assertThat(status.status).isEqualTo("COMPLETED")
        assertThat(status.result).isEqualTo(result)
        assertThat(status.startedAt).isNotNull()
        assertThat(status.completedAt).isNotNull()
        verify(exactly = 1) { bulkImportService.execute(request, 7L, "Bulk import API") }
    }

    @Test
    fun `second import is refused while the first is active and allowed after it finishes`() {
        every { bulkImportService.execute(any(), any(), any()) } returns result
        val first = service.start(request, 7L, "admin")

        assertThatThrownBy { service.start(request, 7L, "other-admin") }
            .isInstanceOf(BulkImportJobConflictException::class.java)
            .extracting("existingJobId").isEqualTo(first.jobId)

        executor.runAll()
        assertThat(service.start(request, 7L, "admin").jobId).isNotEqualTo(first.jobId)
    }

    @Test
    fun `job is invisible to another user and unknown ids are null`() {
        every { bulkImportService.execute(any(), any(), any()) } returns result
        val started = service.start(request, 7L, "admin")

        assertThat(service.getStatus(started.jobId, "other-admin")).isNull()
        assertThat(service.getStatus("no-such-job", "admin")).isNull()
    }

    @Test
    fun `operator-facing rejection keeps its message, unexpected failure is generic`() {
        every { bulkImportService.execute(any(), any(), any()) } throws
            IllegalArgumentException("Saved onboarding configuration is not ready")
        val rejected = service.start(request, 7L, "admin")
        executor.runAll()
        service.getStatus(rejected.jobId, "admin")!!.let {
            assertThat(it.status).isEqualTo("FAILED")
            assertThat(it.errorMessage).isEqualTo("Saved onboarding configuration is not ready")
        }

        every { bulkImportService.execute(any(), any(), any()) } throws
            IllegalStateException("could not execute statement; SQL [insert into user_mapping ...]")
        val failed = service.start(request, 7L, "admin")
        executor.runAll()
        service.getStatus(failed.jobId, "admin")!!.let {
            assertThat(it.status).isEqualTo("FAILED")
            assertThat(it.errorMessage).isEqualTo(UserMappingBulkImportJobService.GENERIC_FAILURE)
        }
    }

    @Test
    fun `finished jobs are bounded`() {
        every { bulkImportService.execute(any(), any(), any()) } returns result
        val ids = (1..UserMappingBulkImportJobService.MAX_RETAINED_FINISHED + 2).map {
            service.start(request, 7L, "admin").jobId.also { executor.runAll() }
        }
        // Eviction runs on the next start; the oldest finished jobs go first.
        service.start(request, 7L, "admin")
        assertThat(service.getStatus(ids.first(), "admin")).isNull()
        assertThat(service.getStatus(ids.last(), "admin")).isNotNull()
    }
}
