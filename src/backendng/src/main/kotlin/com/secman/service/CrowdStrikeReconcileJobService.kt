package com.secman.service

import com.secman.constants.AssetOwners
import com.secman.domain.CrowdStrikeReconcileJob
import com.secman.domain.ReconcileJobStatus
import com.secman.dto.ReconcileJobStartedResponse
import com.secman.dto.ReconcileJobStatusResponse
import com.secman.dto.ReconcileStaleResult
import com.secman.dto.ReconcileStaleVulnerabilitiesRequest
import com.secman.dto.ReconcileStaleVulnerabilitiesResponse
import com.secman.repository.CrowdStrikeReconcileJobRepository
import io.micronaut.scheduling.TaskExecutors
import jakarta.inject.Named
import jakarta.inject.Singleton
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import jakarta.transaction.Transactional.TxType
import org.slf4j.LoggerFactory
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.ExecutorService

/**
 * Thrown by [CrowdStrikeReconcileJobService.startReconcile] when another reconcile job
 * is already PENDING/RUNNING. Carries the running job's id so the controller can
 * surface it in the 409 body.
 */
class ReconcileJobConflictException(val existingJobId: String) :
    RuntimeException("A reconcile job is already running: $existingJobId")

/**
 * Runs the CrowdStrike reconcile-stale sweep as a background job.
 *
 * The synchronous sweep exceeded nginx's 60s proxy timeout on large tables (~65s on
 * 1.3M vulnerability rows → 504 to the CLI while the backend completed fine). This
 * service converts it to the ExportJobService fire-and-poll pattern: a job row is
 * created and committed, the heavy work runs on the IO executor, and the CLI polls
 * the status endpoint until a terminal state.
 *
 * The request payload (incl. the queriedHosts list, ~2000 entries) is never persisted —
 * it travels in the executor closure. A backend restart mid-job therefore orphans the
 * row in RUNNING; [autoFailStuckJobs] reclaims such zombies on the next start.
 */
@Singleton
open class CrowdStrikeReconcileJobService(
    private val jobRepository: CrowdStrikeReconcileJobRepository,
    private val importService: CrowdStrikeVulnerabilityImportService,
    @Named(TaskExecutors.IO) private val executorService: ExecutorService,
    private val entityManager: EntityManager
) {
    private val log = LoggerFactory.getLogger(CrowdStrikeReconcileJobService::class.java)

    /**
     * Provider for self-reference so internal calls to @Transactional(REQUIRES_NEW) members
     * go through the AOP proxy (same pattern as ExportJobService).
     */
    @jakarta.inject.Inject
    private lateinit var selfProvider: jakarta.inject.Provider<CrowdStrikeReconcileJobService>

    companion object {
        /**
         * A PENDING/RUNNING job older than this is considered a zombie (backend restart
         * mid-job — the in-memory payload is gone, the sweep will never finish) and is
         * auto-failed at the next start attempt. The sweep itself takes ~1-2 min today;
         * 30 min leaves ample headroom for table growth.
         */
        const val STUCK_JOB_TIMEOUT_MIN = 30L

        private val RUNNING_STATUSES = listOf(ReconcileJobStatus.PENDING, ReconcileJobStatus.RUNNING)
    }

    /**
     * Create a reconcile job and kick off the sweep on the IO executor.
     *
     * @return 202-body payload with the new jobId
     * @throws ReconcileJobConflictException when another job is already PENDING/RUNNING
     */
    open fun startReconcile(
        username: String,
        request: ReconcileStaleVulnerabilitiesRequest
    ): ReconcileJobStartedResponse {
        selfProvider.get().autoFailStuckJobs()

        val running = jobRepository.findByStatusIn(RUNNING_STATUSES)
        if (running.isNotEmpty()) {
            throw ReconcileJobConflictException(running.first().id)
        }

        val jobId = UUID.randomUUID().toString()
        selfProvider.get().createJobRow(jobId, username)
        log.info("[reconcile {}] job created by {} (severities={}, queriedHosts={})",
            jobId.take(8), username, request.severities, request.queriedHosts?.size ?: 0)

        executorService.submit {
            processInBackground(jobId, request)
        }

        return ReconcileJobStartedResponse(jobId = jobId, status = ReconcileJobStatus.PENDING.name)
    }

    /**
     * Get job status for polling. Returns null when the job doesn't exist or belongs
     * to another user (surfaced as 404 by the controller).
     */
    open fun getJobStatus(jobId: String, username: String): ReconcileJobStatusResponse? {
        val job = jobRepository.findByIdAndUsername(jobId, username).orElse(null) ?: return null
        val result = if (job.status == ReconcileJobStatus.COMPLETED) {
            ReconcileStaleVulnerabilitiesResponse(
                rowsDeleted = job.rowsDeleted ?: 0,
                cutoff = job.cutoff ?: job.createdAt,
                severities = job.severities?.split(",")?.filter { it.isNotBlank() } ?: emptyList(),
                owner = AssetOwners.CROWDSTRIKE_IMPORT,
                aborted = job.aborted ?: false,
                abortReason = job.abortReason
            )
        } else null
        return ReconcileJobStatusResponse(
            jobId = job.id,
            status = job.status.name,
            createdAt = job.createdAt,
            startedAt = job.startedAt,
            completedAt = job.completedAt,
            errorMessage = job.errorMessage,
            result = result
        )
    }

    private fun processInBackground(jobId: String, request: ReconcileStaleVulnerabilitiesRequest) {
        val shortId = jobId.take(8)
        try {
            selfProvider.get().markJobAsRunning(jobId)
            val result = selfProvider.get().runReconcileInNewTransaction(request)
            selfProvider.get().markJobAsCompleted(jobId, result, request.importStartedAt, request.severities)
            log.info("[reconcile {}] completed: rowsDeleted={}, aborted={}", shortId, result.rowsDeleted, result.aborted)
        } catch (e: Exception) {
            log.error("[reconcile {}] failed", shortId, e)
            try {
                selfProvider.get().markJobAsFailed(jobId, e.message?.take(1000) ?: "Unknown error")
            } catch (updateEx: Exception) {
                log.error("[reconcile {}] failed to update status after error", shortId, updateEx)
            }
        }
    }

    /**
     * Run the sweep in a fresh transaction.
     *
     * TxType.REQUIRES_NEW is CRITICAL: this runs on the IO executor, and Micronaut's
     * instrumented executor propagates the submitter's transactional context — which
     * has already closed by the time we run. The import service's own @Transactional
     * (REQUIRED) then joins this fresh TX, so the sweep stays one transaction, same
     * semantics as the old synchronous endpoint. (See ExportJobService.loadJobForProcessing.)
     */
    @Transactional(TxType.REQUIRES_NEW)
    open fun runReconcileInNewTransaction(request: ReconcileStaleVulnerabilitiesRequest): ReconcileStaleResult {
        return importService.reconcileStaleCrowdStrikeImports(
            cutoff = request.importStartedAt,
            severities = request.severities,
            queriedHosts = request.queriedHosts ?: emptyList()
        )
    }

    /**
     * Insert the job row in its own committed transaction so the background thread
     * (and concurrent starters' guards) can see it. REQUIRES_NEW — see note on
     * runReconcileInNewTransaction.
     */
    @Transactional(TxType.REQUIRES_NEW)
    open fun createJobRow(jobId: String, username: String): CrowdStrikeReconcileJob {
        val job = CrowdStrikeReconcileJob(id = jobId, username = username)
        val saved = jobRepository.save(job)
        // Force immediate flush to avoid MariaDB JDBC driver batching bug
        // (IndexOutOfBoundsException in handleStandardResults) — same as ExportJobService.
        entityManager.flush()
        return saved
    }

    /**
     * Mark a job as running. REQUIRES_NEW — see note on runReconcileInNewTransaction.
     */
    @Transactional(TxType.REQUIRES_NEW)
    open fun markJobAsRunning(jobId: String) {
        val job = jobRepository.findById(jobId).orElse(null) ?: return
        if (job.status != ReconcileJobStatus.PENDING) {
            log.warn("Skipping RUNNING transition for reconcile job {} - current status is {}", jobId, job.status)
            return
        }
        job.status = ReconcileJobStatus.RUNNING
        job.startedAt = LocalDateTime.now()
        jobRepository.update(job)
    }

    /**
     * Mark a job as completed with the sweep result. REQUIRES_NEW — see note on
     * runReconcileInNewTransaction.
     */
    @Transactional(TxType.REQUIRES_NEW)
    open fun markJobAsCompleted(
        jobId: String,
        result: ReconcileStaleResult,
        cutoff: LocalDateTime,
        severities: List<String>
    ) {
        val job = jobRepository.findById(jobId).orElse(null) ?: return
        // Status guard: never resurrect a job auto-failed as stuck while the sweep ran.
        if (!job.isRunning()) {
            log.warn("Skipping COMPLETED transition for reconcile job {} - already terminal ({})", jobId, job.status)
            return
        }
        job.status = ReconcileJobStatus.COMPLETED
        job.completedAt = LocalDateTime.now()
        job.rowsDeleted = result.rowsDeleted
        job.cutoff = cutoff
        job.severities = severities.joinToString(",").take(500)
        job.aborted = result.aborted
        job.abortReason = result.abortReason?.take(500)
        jobRepository.update(job)
    }

    /**
     * Mark a job as failed. REQUIRES_NEW — see note on runReconcileInNewTransaction.
     */
    @Transactional(TxType.REQUIRES_NEW)
    open fun markJobAsFailed(jobId: String, errorMessage: String) {
        val job = jobRepository.findById(jobId).orElse(null) ?: return
        if (!job.isRunning()) {
            log.warn("Skipping FAILED transition for reconcile job {} - already terminal ({})", jobId, job.status)
            return
        }
        job.status = ReconcileJobStatus.FAILED
        job.completedAt = LocalDateTime.now()
        job.errorMessage = errorMessage
        jobRepository.update(job)
    }

    /**
     * Auto-fail PENDING/RUNNING jobs older than [STUCK_JOB_TIMEOUT_MIN] minutes (zombies
     * from a backend restart mid-job). Called before each start so a stuck row never
     * blocks reconcile forever. REQUIRES_NEW — see note on runReconcileInNewTransaction.
     *
     * @return number of jobs reclaimed
     */
    @Transactional(TxType.REQUIRES_NEW)
    open fun autoFailStuckJobs(): Int {
        val staleThreshold = LocalDateTime.now().minusMinutes(STUCK_JOB_TIMEOUT_MIN)
        val stuck = jobRepository.findByStatusIn(RUNNING_STATUSES)
            .filter { it.createdAt.isBefore(staleThreshold) }
        stuck.forEach { job ->
            val previousStatus = job.status
            log.warn("Auto-failing stuck reconcile job {} (status: {}, created: {})",
                job.id, previousStatus, job.createdAt)
            job.status = ReconcileJobStatus.FAILED
            job.completedAt = LocalDateTime.now()
            job.errorMessage = "Auto-failed: stuck in $previousStatus for >${STUCK_JOB_TIMEOUT_MIN}min (backend restart?)"
            jobRepository.update(job)
        }
        return stuck.size
    }
}
