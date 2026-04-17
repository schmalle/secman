package com.secman.service

import com.secman.config.MemoryOptimizationConfig
import com.secman.util.ExcelSanitizer
import com.secman.domain.ExportJob
import com.secman.domain.ExportJobStatus
import com.secman.domain.ExportType
import com.secman.dto.ExportJobDto
import com.secman.dto.VulnerabilityExportDto
import com.secman.repository.ExportJobRepository
import io.micronaut.context.annotation.Value
import io.micronaut.scheduling.TaskExecutors
import io.micronaut.scheduling.annotation.Scheduled
import io.micronaut.security.authentication.Authentication
import jakarta.inject.Named
import jakarta.inject.Singleton
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import jakarta.transaction.Transactional.TxType
import java.util.concurrent.ExecutorService
import org.apache.poi.ss.usermodel.CellStyle
import org.apache.poi.ss.usermodel.FillPatternType
import org.apache.poi.ss.usermodel.IndexedColors
import org.apache.poi.ss.usermodel.Workbook
import org.apache.poi.xssf.streaming.SXSSFWorkbook
import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.*

/**
 * Service for managing background export jobs
 * Feature: Vulnerability Export Performance Optimization - Background Job Pattern
 * Feature: 073-memory-optimization (configurable batch size)
 *
 * Handles:
 * - Starting export jobs asynchronously
 * - Tracking progress
 * - Writing Excel files to temporary storage
 * - Cleanup of old export files
 *
 * Rate Limiting:
 * - Max 1 concurrent export per user per export type
 * - Max 5 concurrent exports globally (across all types and users)
 *
 * Memory Optimization (Feature 073):
 * - Uses SXSSFWorkbook with 100-row memory window (streaming write)
 * - Writes directly to Excel during fetch (write-on-fetch pattern)
 * - Configurable batch size via MEMORY_BATCH_SIZE environment variable
 */
@Singleton
open class ExportJobService(
    private val exportJobRepository: ExportJobRepository,
    private val vulnerabilityService: VulnerabilityService,
    private val assetFilterService: AssetFilterService,
    @Named(TaskExecutors.IO) private val executorService: ExecutorService,
    private val entityManager: EntityManager,
    private val memoryConfig: MemoryOptimizationConfig,
    @Value("\${secman.export.directory:exports}") private val exportDirectory: String,
    @Value("\${secman.export.max-concurrent-per-user:1}") private val maxConcurrentPerUser: Int,
    @Value("\${secman.export.max-concurrent-global:5}") private val maxConcurrentGlobal: Int,
    @Value("\${secman.export.file-retention-hours:24}") private val fileRetentionHours: Long
) {
    private val log = LoggerFactory.getLogger(ExportJobService::class.java)
    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    companion object {
        // Lifecycle stage labels persisted to ExportJob.currentStage so the UI can
        // surface "Counting rows...", "Writing file...", etc. instead of a bare %.
        const val STAGE_STARTING = "STARTING"
        const val STAGE_COUNTING = "COUNTING"
        const val STAGE_EXPORTING = "EXPORTING"
        const val STAGE_WRITING_FILE = "WRITING_FILE"
        const val STAGE_FINALIZING = "FINALIZING"

        // Job is considered stale if (lastHeartbeatAt ?: createdAt) is older
        // than this many minutes. The worker heartbeats every ~2s during
        // EXPORTING and immediately on entering the background thread, so
        // anything >5 minutes of silence is almost certainly dead.
        const val HEARTBEAT_STALE_MIN = 5L
    }

    init {
        // Ensure export directory exists
        val exportDir = File(exportDirectory)
        if (!exportDir.exists()) {
            exportDir.mkdirs()
            log.info("Created export directory: {}", exportDir.absolutePath)
        }
    }

    /**
     * Start a new export job
     *
     * @param authentication Current user authentication
     * @param exportType Type of export (default: VULNERABILITIES)
     * @return Created job DTO
     * @throws IllegalStateException if rate limits exceeded
     */
    @Transactional
    open fun startExport(authentication: Authentication, exportType: ExportType = ExportType.VULNERABILITIES): ExportJobDto {
        val username = authentication.name
        log.info("Starting export job for user: {}, type: {}", username, exportType)

        // Check rate limits
        val runningStatuses = listOf(ExportJobStatus.PENDING, ExportJobStatus.PROCESSING)

        // Auto-reset zombie jobs that got stuck (e.g., server restart during processing)
        autoResetStaleJobs(username, exportType, runningStatuses)

        val userRunningJobs = exportJobRepository.countByUsernameAndExportTypeAndStatusIn(username, exportType, runningStatuses)
        if (userRunningJobs >= maxConcurrentPerUser) {
            log.warn("User {} already has {} running {} export(s), max allowed: {}", username, userRunningJobs, exportType, maxConcurrentPerUser)
            throw IllegalStateException("You already have a ${exportType.name.lowercase()} export in progress. Please wait for it to complete.")
        }

        val globalRunningJobs = exportJobRepository.countByStatusIn(runningStatuses)
        if (globalRunningJobs >= maxConcurrentGlobal) {
            log.warn("Global export limit reached: {} running, max allowed: {}", globalRunningJobs, maxConcurrentGlobal)
            throw IllegalStateException("Server is busy with exports. Please try again in a few minutes.")
        }

        // Create job
        val jobId = UUID.randomUUID().toString()
        val job = ExportJob(
            id = jobId,
            username = username,
            status = ExportJobStatus.PENDING,
            exportType = exportType
        )

        val savedJob = exportJobRepository.save(job)
        // Force immediate flush to avoid MariaDB JDBC driver batching bug
        // (IndexOutOfBoundsException in handleStandardResults)
        entityManager.flush()
        log.info("Created export job: {}", jobId)

        // Start async processing using ExecutorService
        // We need to capture the authentication info since Authentication may not be available in background thread
        val isAdmin = authentication.roles.contains("ADMIN") || authentication.roles.contains("SECCHAMPION")
        val accessibleAssetIds = if (isAdmin) {
            emptySet()
        } else {
            assetFilterService.getAccessibleAssets(authentication)
                .mapNotNull { it.id }
                .toSet()
        }

        executorService.submit {
            processExportInBackground(jobId, username, isAdmin, accessibleAssetIds)
        }

        return ExportJobDto.fromEntity(savedJob)
    }

    /**
     * Auto-reset export jobs stuck in PENDING/PROCESSING for over 30 minutes.
     * This handles zombie jobs left behind by server restarts or unhandled errors
     * in background threads, preventing users from being permanently blocked.
     */
    private fun autoResetStaleJobs(username: String, exportType: ExportType, runningStatuses: List<ExportJobStatus>) {
        // Use (lastHeartbeatAt ?: createdAt) as the "last known activity" timestamp
        // and apply a single HEARTBEAT_STALE_MIN threshold. Under the new code path
        // every healthy job heartbeats within seconds of entering the background
        // thread (stage transitions STARTING/COUNTING immediately record one). So:
        //   * healthy job       -> recent heartbeat -> not stale
        //   * crashed-new job   -> null heartbeat, createdAt > 5min -> STALE
        //   * legacy stuck job  -> null heartbeat, createdAt > 5min -> STALE
        //   * just-submitted    -> null heartbeat, createdAt < 5min -> not stale (yet)
        val now = LocalDateTime.now()
        val staleThreshold = now.minusMinutes(HEARTBEAT_STALE_MIN)

        val runningJobs = exportJobRepository.findByUsernameAndStatusIn(username, runningStatuses)

        runningJobs
            .filter { it.exportType == exportType }
            .filter { job -> (job.lastHeartbeatAt ?: job.createdAt).isBefore(staleThreshold) }
            .forEach { job ->
                val effective = job.lastHeartbeatAt ?: job.createdAt
                val staleReason = if (job.lastHeartbeatAt != null) {
                    "no heartbeat for >${HEARTBEAT_STALE_MIN}min (last=${job.lastHeartbeatAt})"
                } else {
                    "never heartbeated; created ${job.createdAt} (>${HEARTBEAT_STALE_MIN}min ago)"
                }
                log.warn("Auto-resetting stale export job: {} ({}, status: {}, stage: {}, effectiveActivity: {})",
                    job.id, staleReason, job.status, job.currentStage, effective)
                job.status = ExportJobStatus.FAILED
                job.completedAt = LocalDateTime.now()
                job.errorMessage = "Auto-reset: $staleReason"
                exportJobRepository.update(job)
            }
    }

    /**
     * Get export job status
     *
     * @param jobId Job ID
     * @param username Username for authorization
     * @return Job DTO or null if not found
     */
    fun getJobStatus(jobId: String, username: String): ExportJobDto? {
        val job = exportJobRepository.findByIdAndUsername(jobId, username).orElse(null)
        return job?.let { ExportJobDto.fromEntity(it) }
    }

    /**
     * Get export file for download
     *
     * @param jobId Job ID
     * @param username Username for authorization
     * @return File or null if not found/not ready
     */
    fun getExportFile(jobId: String, username: String): File? {
        val job = exportJobRepository.findByIdAndUsername(jobId, username).orElse(null)

        if (job == null) {
            log.warn("Job not found: {}", jobId)
            return null
        }

        if (!job.isDownloadable()) {
            log.warn("Job {} is not downloadable, status: {}", jobId, job.status)
            return null
        }

        val file = File(job.filePath!!)
        // Path traversal prevention
        val exportDir = File(exportDirectory).canonicalFile
        if (!file.canonicalFile.startsWith(exportDir)) {
            log.error("Path traversal attempt in export file: {}", job.filePath)
            return null
        }
        if (!file.exists()) {
            log.error("Export file not found: {}", job.filePath)
            // Mark job as expired
            job.status = ExportJobStatus.EXPIRED
            job.errorMessage = "Export file was cleaned up"
            exportJobRepository.update(job)
            return null
        }

        return file
    }

    /**
     * Get recent export jobs for a user
     */
    fun getRecentJobs(username: String, limit: Int = 10): List<ExportJobDto> {
        return exportJobRepository.findByUsernameOrderByCreatedAtDesc(username)
            .take(limit)
            .map { ExportJobDto.fromEntity(it) }
    }

    /**
     * Cancel a running export job
     */
    @Transactional
    open fun cancelJob(jobId: String, username: String): Boolean {
        val job = exportJobRepository.findByIdAndUsername(jobId, username).orElse(null) ?: return false

        if (!job.isRunning()) {
            log.warn("Cannot cancel job {} - not running (status: {})", jobId, job.status)
            return false
        }

        job.status = ExportJobStatus.CANCELLED
        job.completedAt = LocalDateTime.now()
        job.errorMessage = "Cancelled by user"
        exportJobRepository.update(job)

        log.info("Cancelled export job: {}", jobId)
        return true
    }

    /**
     * Reset all stuck jobs for a user
     * Useful when jobs get stuck in PENDING/PROCESSING status due to server restart or errors
     *
     * @param username Username to reset jobs for
     * @return Number of jobs reset
     */
    @Transactional
    open fun resetStuckJobs(username: String): Int {
        val runningStatuses = listOf(ExportJobStatus.PENDING, ExportJobStatus.PROCESSING)
        val stuckJobs = exportJobRepository.findByUsernameAndStatusIn(username, runningStatuses)

        if (stuckJobs.isEmpty()) {
            log.info("No stuck jobs found for user: {}", username)
            return 0
        }

        stuckJobs.forEach { job ->
            log.info("Resetting stuck job: {} (status: {}, created: {})", job.id, job.status, job.createdAt)
            job.status = ExportJobStatus.FAILED
            job.completedAt = LocalDateTime.now()
            job.errorMessage = "Reset by user - job was stuck"
            exportJobRepository.update(job)
        }

        log.info("Reset {} stuck jobs for user: {}", stuckJobs.size, username)
        return stuckJobs.size
    }

    /**
     * Process export in background thread
     * Called via ExecutorService to ensure true async execution
     *
     * Uses separate @Transactional methods (not self-invocation) to ensure
     * proper Hibernate Session management in background threads.
     * Each database operation runs in its own short transaction.
     *
     * @param jobId Job ID to process
     * @param username Username for logging
     * @param isAdmin Whether user is admin (for access control)
     * @param accessibleAssetIds Pre-computed set of accessible asset IDs
     */
    private fun processExportInBackground(
        jobId: String,
        username: String,
        isAdmin: Boolean,
        accessibleAssetIds: Set<Long>
    ) {
        val shortId = jobId.take(8)
        val originalThreadName = Thread.currentThread().name
        Thread.currentThread().name = "export-job-$shortId"
        val backgroundStart = System.currentTimeMillis()

        try {
            log.info("[export {}] background processing started (user={}, thread={})",
                shortId, username, originalThreadName)

            // Load job in its own transaction
            val job = loadJobForProcessing(jobId)
            if (job == null) {
                log.error("[export {}] job not found for background processing", shortId)
                return
            }

            val pendingMs = System.currentTimeMillis() -
                job.createdAt.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
            log.info("[export {}] QUEUED -> PROCESSING after {}ms in queue", shortId, pendingMs)

            // Check if cancelled before starting
            if (job.status == ExportJobStatus.CANCELLED) {
                log.info("[export {}] was cancelled before processing started", shortId)
                return
            }

            // Update status to processing (separate transaction)
            markJobAsProcessing(jobId)
            updateJobStage(jobId, STAGE_STARTING)

            when (job.exportType) {
                ExportType.VULNERABILITIES -> processVulnerabilityExport(jobId, isAdmin, accessibleAssetIds)
                else -> throw IllegalArgumentException("Unsupported export type: ${job.exportType}")
            }

            val totalMs = System.currentTimeMillis() - backgroundStart
            log.info("[export {}] background processing finished in {}ms", shortId, totalMs)
        } catch (e: Exception) {
            log.error("[export {}] failed", shortId, e)
            // Update job status in a separate transaction
            try {
                markJobAsFailed(jobId, e.message?.take(1000) ?: "Unknown error")
            } catch (updateEx: Exception) {
                log.error("[export {}] failed to update status after error", shortId, updateEx)
            }
        } finally {
            Thread.currentThread().name = originalThreadName
        }
    }

    // ============================================================
    // Transactional helper methods for background thread operations
    // These methods run in their own transactions via AOP proxy
    // ============================================================

    /**
     * Load a job by ID in its own transaction.
     *
     * TxType.REQUIRES_NEW is CRITICAL here: this method is called from a
     * background thread via executorService.submit. Micronaut's instrumented
     * IO executor propagates the submitter's transactional context, and by
     * the time we run the submitter's transaction (and its EntityManager)
     * has already closed. REQUIRES_NEW forces a fresh session each call.
     * All helpers below follow the same pattern for the same reason.
     */
    @Transactional(TxType.REQUIRES_NEW)
    open fun loadJobForProcessing(jobId: String): ExportJob? {
        return exportJobRepository.findById(jobId).orElse(null)
    }

    /**
     * Mark a job as processing. REQUIRES_NEW — see note on loadJobForProcessing.
     */
    @Transactional(TxType.REQUIRES_NEW)
    open fun markJobAsProcessing(jobId: String) {
        val job = exportJobRepository.findById(jobId).orElse(null) ?: return
        job.status = ExportJobStatus.PROCESSING
        job.startedAt = LocalDateTime.now()
        exportJobRepository.update(job)
    }

    /**
     * Check if a job has been cancelled. REQUIRES_NEW — see note on loadJobForProcessing.
     */
    @Transactional(TxType.REQUIRES_NEW)
    open fun isJobCancelled(jobId: String): Boolean {
        val job = exportJobRepository.findById(jobId).orElse(null)
        return job?.status == ExportJobStatus.CANCELLED
    }

    /**
     * Update job total items count. REQUIRES_NEW — see note on loadJobForProcessing.
     */
    @Transactional(TxType.REQUIRES_NEW)
    open fun updateJobTotalItems(jobId: String, totalItems: Long) {
        val job = exportJobRepository.findById(jobId).orElse(null) ?: return
        job.totalItems = totalItems
        exportJobRepository.update(job)
    }

    /**
     * Update job progress. REQUIRES_NEW — see note on loadJobForProcessing.
     */
    @Transactional(TxType.REQUIRES_NEW)
    open fun updateJobProgress(jobId: String, processedItems: Long) {
        val job = exportJobRepository.findById(jobId).orElse(null) ?: return
        job.processedItems = processedItems
        exportJobRepository.update(job)
    }

    /**
     * Mark a job as completed with file info. REQUIRES_NEW — see note on loadJobForProcessing.
     */
    @Transactional(TxType.REQUIRES_NEW)
    open fun markJobAsCompleted(jobId: String, filePath: String, fileName: String, fileSizeBytes: Long, totalItems: Long) {
        val job = exportJobRepository.findById(jobId).orElse(null) ?: return
        job.status = ExportJobStatus.COMPLETED
        job.completedAt = LocalDateTime.now()
        job.filePath = filePath
        job.fileName = fileName
        job.fileSizeBytes = fileSizeBytes
        job.processedItems = totalItems
        exportJobRepository.update(job)
    }

    /**
     * Mark a job as failed. REQUIRES_NEW — see note on loadJobForProcessing.
     */
    @Transactional(TxType.REQUIRES_NEW)
    open fun markJobAsFailed(jobId: String, errorMessage: String) {
        val job = exportJobRepository.findById(jobId).orElse(null) ?: return
        job.status = ExportJobStatus.FAILED
        job.completedAt = LocalDateTime.now()
        job.errorMessage = errorMessage
        exportJobRepository.update(job)
    }

    /**
     * Update the currentStage field and heartbeat so the frontend can show progress
     * phase-by-phase even before item counts are known.
     * REQUIRES_NEW — see note on loadJobForProcessing.
     */
    @Transactional(TxType.REQUIRES_NEW)
    open fun updateJobStage(jobId: String, stage: String) {
        val job = exportJobRepository.findById(jobId).orElse(null) ?: return
        job.currentStage = stage
        job.lastHeartbeatAt = LocalDateTime.now()
        exportJobRepository.update(job)
    }

    /**
     * Update lastHeartbeatAt only. Called periodically during long sub-steps (e.g. counting)
     * so stall detection in the UI can distinguish "still working" from "dead".
     * REQUIRES_NEW — see note on loadJobForProcessing.
     */
    @Transactional(TxType.REQUIRES_NEW)
    open fun recordHeartbeat(jobId: String) {
        val job = exportJobRepository.findById(jobId).orElse(null) ?: return
        job.lastHeartbeatAt = LocalDateTime.now()
        exportJobRepository.update(job)
    }

    /**
     * Fetch a page of vulnerabilities through VulnerabilityService in a fresh
     * transaction. Wrapping this call is necessary because VulnerabilityService
     * is annotated @Transactional (default REQUIRED), so from the background
     * thread it joins the propagated HTTP-request transaction (which is closed
     * by the time we run) and throws "Session/EntityManager is closed".
     *
     * REQUIRES_NEW opens a brand-new transaction/session for each batch, which
     * is exactly what we want: one short-lived TX per batch keeps JPA heap
     * pressure bounded.
     */
    @Transactional(TxType.REQUIRES_NEW)
    open fun fetchVulnerabilityPageForExport(
        accessibleAssetIds: Set<Long>,
        isAdmin: Boolean,
        page: Int,
        size: Int
    ): com.secman.dto.PaginatedVulnerabilitiesResponse {
        return vulnerabilityService.getCurrentVulnerabilitiesOptimized(
            accessibleAssetIds = accessibleAssetIds,
            isAdmin = isAdmin,
            severity = null,
            system = null,
            exceptionStatus = "not_excepted",
            product = null,
            adDomain = null,
            cloudAccountId = null,
            page = page,
            size = size
        )
    }

    /**
     * Process vulnerability export
     * Uses jobId and calls transactional helper methods for all DB operations.
     *
     * Progress lifecycle:
     *   STARTING -> COUNTING -> EXPORTING -> WRITING_FILE -> FINALIZING -> (COMPLETED)
     *
     * Correctness notes:
     * - Count query and export loop now share identical filters ("not_excepted"),
     *   so processedItems == totalItems on success (previously diverged and progress
     *   never reached 100%).
     * - processedItems increments by actual returned rows, not (page+1)*batchSize,
     *   so the final (partial) batch doesn't over-count.
     */
    private fun processVulnerabilityExport(
        jobId: String,
        isAdmin: Boolean,
        accessibleAssetIds: Set<Long>
    ) {
        val shortId = jobId.take(8)

        // --- STAGE 1: COUNTING ------------------------------------------------
        updateJobStage(jobId, STAGE_COUNTING)
        log.info("[export {}] COUNTING started (isAdmin={}, accessibleAssets={})",
            shortId, isAdmin, if (isAdmin) "ALL" else accessibleAssetIds.size.toString())

        val countStart = System.currentTimeMillis()
        // IMPORTANT: use the SAME filter as the export loop below. If this
        // diverges, progressPercent will never reach 100.
        // Use the REQUIRES_NEW wrapper — see fetchVulnerabilityPageForExport.
        val firstPage = fetchVulnerabilityPageForExport(
            accessibleAssetIds = accessibleAssetIds,
            isAdmin = isAdmin,
            page = 0,
            size = 1
        )
        val countMs = System.currentTimeMillis() - countStart
        val totalItems = firstPage.totalElements
        updateJobTotalItems(jobId, totalItems)
        recordHeartbeat(jobId)
        log.info("[export {}] COUNTING done in {}ms: totalItems={}", shortId, countMs, totalItems)

        // --- STAGE 2: EXPORTING ----------------------------------------------
        updateJobStage(jobId, STAGE_EXPORTING)

        // Create output file
        val dateStr = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
        val fileName = "vulnerabilities_export_${dateStr}_${shortId}.xlsx"
        val filePath = "$exportDirectory/$fileName"

        // Create streaming workbook and write directly to file
        val workbook = SXSSFWorkbook(100)
        workbook.setCompressTempFiles(true)
        val exportStart = System.currentTimeMillis()

        try {
            val sheet = workbook.createSheet("Vulnerabilities")
            val styles = createVulnerabilityStyles(workbook)
            createHeaderRow(sheet, styles)

            var rowNum = 1
            var page = 0
            val batchSize = memoryConfig.batchSize
            var hasMore = true
            var lastProgressUpdate = System.currentTimeMillis()
            var processedItems = 0L

            while (hasMore) {
                // Check for cancellation (separate transaction)
                if (isJobCancelled(jobId)) {
                    log.info("[export {}] cancelled during EXPORTING at row {}/{}",
                        shortId, processedItems, totalItems)
                    workbook.close()
                    return
                }

                val batchStart = System.currentTimeMillis()
                // Use the REQUIRES_NEW wrapper — see fetchVulnerabilityPageForExport.
                val response = fetchVulnerabilityPageForExport(
                    accessibleAssetIds = accessibleAssetIds,
                    isAdmin = isAdmin,
                    page = page,
                    size = batchSize
                )

                response.content.forEach { vuln ->
                    val dto = VulnerabilityExportDto.fromVulnerabilityWithException(vuln)
                    createVulnerabilityRow(sheet, rowNum++, dto, styles)
                }

                // Correct: count ACTUAL returned rows, not (page+1)*batchSize
                processedItems += response.content.size
                val batchMs = System.currentTimeMillis() - batchStart

                // Update progress every 2 seconds of wall time
                val now = System.currentTimeMillis()
                if (now - lastProgressUpdate > 2000 || !response.hasNext) {
                    updateJobProgress(jobId, processedItems)
                    recordHeartbeat(jobId)
                    lastProgressUpdate = now
                    val elapsed = (now - exportStart).coerceAtLeast(1L)
                    val rowsPerSec = processedItems * 1000 / elapsed
                    val etaSec = if (rowsPerSec > 0 && totalItems > processedItems) {
                        (totalItems - processedItems) / rowsPerSec
                    } else 0
                    val pct = if (totalItems > 0) (processedItems * 100 / totalItems) else 0
                    log.info(
                        "[export {}] EXPORTING batch {}: +{} rows ({}/{} = {}%, {} rows/s, batch {}ms, ETA {}s)",
                        shortId, page, response.content.size, processedItems, totalItems,
                        pct, rowsPerSec, batchMs, etaSec
                    )
                }

                hasMore = response.hasNext
                page++
            }

            // --- STAGE 3: WRITING_FILE ----------------------------------------
            updateJobStage(jobId, STAGE_WRITING_FILE)
            log.info("[export {}] WRITING_FILE: {} rows -> {}", shortId, rowNum - 1, filePath)
            val writeStart = System.currentTimeMillis()

            // Set column widths
            setFixedColumnWidths(sheet)

            // Write to file
            FileOutputStream(filePath).use { fos ->
                workbook.write(fos)
            }
            val writeMs = System.currentTimeMillis() - writeStart

            // --- STAGE 4: FINALIZING ------------------------------------------
            updateJobStage(jobId, STAGE_FINALIZING)
            val file = File(filePath)
            // Pass the actual count we wrote so status reflects reality even if
            // totalItems drifts from live data between count and finish.
            markJobAsCompleted(jobId, filePath, fileName, file.length(), processedItems)

            val totalMs = System.currentTimeMillis() - exportStart
            log.info(
                "[export {}] COMPLETED: rows={}, fileBytes={}, exportMs={}, writeMs={}, countMs={}",
                shortId, rowNum - 1, file.length(), totalMs, writeMs, countMs
            )

        } finally {
            workbook.close()
        }
    }

    /**
     * Scheduled cleanup of old export files
     * Runs every hour
     */
    @Scheduled(fixedDelay = "1h", initialDelay = "5m")
    open fun cleanupOldExports() {
        log.info("Starting export file cleanup (retention: {} hours)", fileRetentionHours)

        val cutoffDate = LocalDateTime.now().minusHours(fileRetentionHours)

        // Find completed jobs older than retention period
        val oldJobs = exportJobRepository.findByStatusAndCompletedAtBefore(
            ExportJobStatus.COMPLETED, cutoffDate
        )

        var filesDeleted = 0
        var bytesFreed = 0L

        oldJobs.forEach { job ->
            try {
                job.filePath?.let { path ->
                    val file = File(path)
                    if (file.exists()) {
                        bytesFreed += file.length()
                        file.delete()
                        filesDeleted++
                    }
                }
                job.status = ExportJobStatus.EXPIRED
                job.filePath = null
                job.errorMessage = "File cleaned up after ${fileRetentionHours}h retention"
                exportJobRepository.update(job)
            } catch (e: Exception) {
                log.error("Failed to cleanup job {}: {}", job.id, e.message)
            }
        }

        // Also cleanup failed/cancelled jobs older than 7 days
        val oldFailedJobs = exportJobRepository.findByCreatedAtBefore(
            LocalDateTime.now().minusDays(7)
        ).filter { it.status in listOf(ExportJobStatus.FAILED, ExportJobStatus.CANCELLED, ExportJobStatus.EXPIRED) }

        oldFailedJobs.forEach { job ->
            exportJobRepository.delete(job)
        }

        if (filesDeleted > 0 || oldFailedJobs.isNotEmpty()) {
            log.info("Cleanup complete: {} files deleted ({} bytes freed), {} old job records removed",
                filesDeleted, bytesFreed, oldFailedJobs.size)
        }
    }

    // Excel generation helper methods (same as VulnerabilityExportService)

    private data class VulnerabilityStyles(
        val header: CellStyle,
        val text: CellStyle,
        val critical: CellStyle,
        val high: CellStyle,
        val medium: CellStyle,
        val low: CellStyle,
        val overdue: CellStyle,
        val excepted: CellStyle,
        val ok: CellStyle,
        val wrapText: CellStyle
    )

    private fun createVulnerabilityStyles(workbook: Workbook): VulnerabilityStyles {
        val headerStyle = workbook.createCellStyle().apply {
            fillForegroundColor = IndexedColors.GREY_25_PERCENT.index
            fillPattern = FillPatternType.SOLID_FOREGROUND
            val font = workbook.createFont()
            font.bold = true
            setFont(font)
        }

        val textStyle = workbook.createCellStyle()

        val criticalStyle = workbook.createCellStyle().apply {
            fillForegroundColor = IndexedColors.RED.index
            fillPattern = FillPatternType.SOLID_FOREGROUND
            val font = workbook.createFont()
            font.bold = true
            font.color = IndexedColors.WHITE.index
            setFont(font)
        }

        val highStyle = workbook.createCellStyle().apply {
            fillForegroundColor = IndexedColors.ORANGE.index
            fillPattern = FillPatternType.SOLID_FOREGROUND
            val font = workbook.createFont()
            font.bold = true
            setFont(font)
        }

        val mediumStyle = workbook.createCellStyle().apply {
            fillForegroundColor = IndexedColors.LIGHT_BLUE.index
            fillPattern = FillPatternType.SOLID_FOREGROUND
        }

        val lowStyle = workbook.createCellStyle().apply {
            fillForegroundColor = IndexedColors.LIGHT_GREEN.index
            fillPattern = FillPatternType.SOLID_FOREGROUND
        }

        val overdueStyle = workbook.createCellStyle().apply {
            fillForegroundColor = IndexedColors.CORAL.index
            fillPattern = FillPatternType.SOLID_FOREGROUND
            val font = workbook.createFont()
            font.bold = true
            setFont(font)
        }

        val exceptedStyle = workbook.createCellStyle().apply {
            fillForegroundColor = IndexedColors.LIGHT_YELLOW.index
            fillPattern = FillPatternType.SOLID_FOREGROUND
        }

        val okStyle = workbook.createCellStyle().apply {
            fillForegroundColor = IndexedColors.LIGHT_GREEN.index
            fillPattern = FillPatternType.SOLID_FOREGROUND
        }

        val wrapTextStyle = workbook.createCellStyle().apply {
            wrapText = true
        }

        return VulnerabilityStyles(
            header = headerStyle,
            text = textStyle,
            critical = criticalStyle,
            high = highStyle,
            medium = mediumStyle,
            low = lowStyle,
            overdue = overdueStyle,
            excepted = exceptedStyle,
            ok = okStyle,
            wrapText = wrapTextStyle
        )
    }

    private fun createHeaderRow(sheet: org.apache.poi.ss.usermodel.Sheet, styles: VulnerabilityStyles) {
        val headerRow = sheet.createRow(0)
        val headers = listOf(
            "Asset Name", "IP", "CVE ID", "Severity", "Product",
            "Days Open", "Scan Date", "Overdue Status", "Has Exception", "Exception Reason"
        )

        headers.forEachIndexed { index, header ->
            val cell = headerRow.createCell(index)
            cell.setCellValue(header)
            cell.cellStyle = styles.header
        }
    }

    private fun createVulnerabilityRow(
        sheet: org.apache.poi.ss.usermodel.Sheet,
        rowNum: Int,
        dto: VulnerabilityExportDto,
        styles: VulnerabilityStyles
    ) {
        val row = sheet.createRow(rowNum)

        row.createCell(0).apply {
            setCellValue(ExcelSanitizer.sanitize(dto.assetName))
            cellStyle = styles.text
        }

        row.createCell(1).apply {
            setCellValue(ExcelSanitizer.sanitize(dto.assetIp))
            cellStyle = styles.text
        }

        row.createCell(2).apply {
            setCellValue(ExcelSanitizer.sanitize(dto.cveId))
            cellStyle = styles.text
        }

        row.createCell(3).apply {
            setCellValue(ExcelSanitizer.sanitize(dto.severity))
            cellStyle = when (dto.severity?.lowercase()) {
                "critical" -> styles.critical
                "high" -> styles.high
                "medium" -> styles.medium
                "low" -> styles.low
                else -> styles.text
            }
        }

        row.createCell(4).apply {
            setCellValue(ExcelSanitizer.sanitize(dto.product))
            cellStyle = styles.wrapText
        }

        row.createCell(5).apply {
            setCellValue(ExcelSanitizer.sanitize(dto.daysOpen))
            cellStyle = styles.text
        }

        row.createCell(6).apply {
            setCellValue(dto.scanDate.format(dateFormatter))
            cellStyle = styles.text
        }

        row.createCell(7).apply {
            setCellValue(ExcelSanitizer.sanitize(dto.overdueStatus))
            cellStyle = when (dto.overdueStatus.uppercase()) {
                "OVERDUE" -> styles.overdue
                "EXCEPTED" -> styles.excepted
                "OK" -> styles.ok
                else -> styles.text
            }
        }

        row.createCell(8).apply {
            setCellValue(if (dto.hasException) "Yes" else "No")
            cellStyle = styles.text
        }

        row.createCell(9).apply {
            setCellValue(ExcelSanitizer.sanitize(dto.exceptionReason))
            cellStyle = styles.wrapText
        }
    }

    private fun setFixedColumnWidths(sheet: org.apache.poi.ss.usermodel.Sheet) {
        sheet.setColumnWidth(0, 7000)  // Asset Name
        sheet.setColumnWidth(1, 4000)  // IP
        sheet.setColumnWidth(2, 5000)  // CVE ID
        sheet.setColumnWidth(3, 3000)  // Severity
        sheet.setColumnWidth(4, 10000) // Product
        sheet.setColumnWidth(5, 3500)  // Days Open
        sheet.setColumnWidth(6, 5500)  // Scan Date
        sheet.setColumnWidth(7, 4000)  // Overdue Status
        sheet.setColumnWidth(8, 4000)  // Has Exception
        sheet.setColumnWidth(9, 10000) // Exception Reason
    }
}
