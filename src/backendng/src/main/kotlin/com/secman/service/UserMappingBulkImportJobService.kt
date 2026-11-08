package com.secman.service

import com.secman.dto.BulkImportJobStartedResponse
import com.secman.dto.BulkImportJobStatusResponse
import com.secman.dto.BulkUserMappingRequest
import com.secman.dto.BulkUserMappingResponse
import io.micronaut.scheduling.TaskExecutors
import jakarta.inject.Named
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService

/**
 * Thrown by [UserMappingBulkImportJobService.start] while another bulk import is still
 * PENDING/RUNNING. Two overlapping imports of the same file could both see an account as
 * brand-new and onboard its owner twice.
 */
class BulkImportJobConflictException(val existingJobId: String) :
    RuntimeException("A user-mapping bulk import is already running: $existingJobId")

/**
 * Runs [UserMappingBulkImportService.execute] as a fire-and-poll job.
 *
 * The synchronous `POST /api/user-mappings/bulk` takes ~80 s on ~2200 accounts (per-workgroup
 * reconciliation dominates), which exceeds the 60 s idle timeout of the load balancer in
 * front of production: the CLI got a 504 while the backend committed fine. Here the
 * request returns a job id at once and the CLI polls, so no proxy timeout applies.
 *
 * State is in memory on purpose: production runs a single backend, and the result carries
 * owner emails that should not be persisted for a polling convenience. A backend restart
 * mid-job drops the job; the CLI then sees 404 and tells the operator to re-run, which is
 * safe because the import is idempotent.
 */
@Singleton
open class UserMappingBulkImportJobService(
    private val bulkImportService: UserMappingBulkImportService,
    @Named(TaskExecutors.IO) private val executorService: ExecutorService
) {
    private val log = LoggerFactory.getLogger(UserMappingBulkImportJobService::class.java)

    /** Job lifecycle; COMPLETED and FAILED are terminal. */
    enum class Status { PENDING, RUNNING, COMPLETED, FAILED }

    /** One import; fields are written by the worker thread and read by pollers. */
    private class Job(val id: String, val username: String, val createdAt: Instant) {
        @Volatile var status: Status = Status.PENDING
        @Volatile var startedAt: Instant? = null
        @Volatile var completedAt: Instant? = null
        @Volatile var errorMessage: String? = null
        @Volatile var result: BulkUserMappingResponse? = null

        fun isActive() = status == Status.PENDING || status == Status.RUNNING
    }

    companion object {
        /** Finished jobs stay pollable this long, then are dropped. */
        val RETENTION: Duration = Duration.ofHours(1)

        /** Upper bound on finished jobs held at once, whatever their age. */
        const val MAX_RETAINED_FINISHED = 20

        const val GENERIC_FAILURE = "Bulk import failed (see server log)"
    }

    private val jobs = ConcurrentHashMap<String, Job>()
    private val startLock = Any()

    /**
     * Register a job and run the import on the IO executor. The caller must already have
     * run [UserMappingBulkImportService.validate] so a bad request is a 400, not a FAILED job.
     *
     * @throws BulkImportJobConflictException while another import is PENDING/RUNNING
     */
    open fun start(
        request: BulkUserMappingRequest,
        requestorUserId: Long?,
        username: String
    ): BulkImportJobStartedResponse {
        val job = synchronized(startLock) {
            evictFinished(Instant.now())
            jobs.values.firstOrNull { it.isActive() }?.let { throw BulkImportJobConflictException(it.id) }
            Job(UUID.randomUUID().toString(), username, Instant.now()).also { jobs[it.id] = it }
        }
        try {
            executorService.submit { run(job, request, requestorUserId) }
        } catch (e: Exception) {
            jobs.remove(job.id)
            throw e
        }
        log.info(
            "AUDIT: operation=BULK_IMPORT_USER_MAPPINGS_JOB_STARTED, actor={}, jobId={}, entries={}, dryRun={}",
            username, job.id, request.mappings.size, request.dryRun
        )
        return BulkImportJobStartedResponse(jobId = job.id, status = job.status.name)
    }

    /** Null when the job is unknown, expired, or was started by someone else (→ 404). */
    open fun getStatus(jobId: String, username: String): BulkImportJobStatusResponse? {
        val job = jobs[jobId]?.takeIf { it.username == username } ?: return null
        return BulkImportJobStatusResponse(
            jobId = job.id,
            status = job.status.name,
            createdAt = job.createdAt,
            startedAt = job.startedAt,
            completedAt = job.completedAt,
            errorMessage = job.errorMessage,
            result = job.result
        )
    }

    private fun run(job: Job, request: BulkUserMappingRequest, requestorUserId: Long?) {
        job.startedAt = Instant.now()
        job.status = Status.RUNNING
        try {
            job.result = bulkImportService.execute(request, requestorUserId, "Bulk import API")
            job.status = Status.COMPLETED
        } catch (e: IllegalArgumentException) {
            // Same contract as the synchronous endpoint's 400: these messages are written for
            // the operator (e.g. "Saved onboarding configuration is not ready").
            log.warn("Bulk import job {} rejected: {}", job.id, e.message)
            job.errorMessage = e.message ?: "Invalid request"
            job.status = Status.FAILED
        } catch (e: Exception) {
            log.error("Bulk import job {} failed", job.id, e)
            job.errorMessage = GENERIC_FAILURE
            job.status = Status.FAILED
        } finally {
            job.completedAt = Instant.now()
            log.info(
                "AUDIT: operation=BULK_IMPORT_USER_MAPPINGS_JOB_FINISHED, actor={}, jobId={}, outcome={}",
                job.username, job.id, job.status
            )
        }
    }

    private fun evictFinished(now: Instant) {
        val finished = jobs.values.filter { !it.isActive() }
        val cutoff = now.minus(RETENTION)
        finished.filter { it.completedAt?.isBefore(cutoff) == true }.forEach { jobs.remove(it.id) }
        val remaining = jobs.values.filter { !it.isActive() }.sortedBy { it.completedAt }
        remaining.take((remaining.size - MAX_RETAINED_FINISHED).coerceAtLeast(0)).forEach { jobs.remove(it.id) }
    }
}
