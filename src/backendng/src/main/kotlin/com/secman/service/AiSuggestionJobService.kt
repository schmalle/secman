package com.secman.service

import com.secman.config.AiRiskAssessmentConfig
import com.secman.domain.AiAnswerSuggestion
import com.secman.domain.AiAnswerSuggestionStatus
import com.secman.domain.AiSuggestionJob
import com.secman.domain.AiSuggestionJobStatus
import com.secman.domain.AiSuggestionScope
import com.secman.domain.Requirement
import com.secman.domain.Response
import com.secman.domain.ResponseSource
import com.secman.domain.RiskAssessment
import com.secman.domain.SuggestedAnswerType
import com.secman.domain.User
import com.secman.dto.AiJobStatusDto
import com.secman.dto.AppliedSuggestionDto
import com.secman.dto.AssessmentContext
import com.secman.dto.Citation
import com.secman.dto.JobProgressEvent
import com.secman.dto.StartAiJobRequest
import com.secman.dto.StartAiJobResponse
import com.secman.dto.SuggestionResult
import com.secman.repository.AiAnswerSuggestionRepository
import com.secman.repository.AiSuggestionJobRepository
import com.secman.repository.RequirementRepository
import com.secman.repository.ResponseRepository
import com.secman.repository.UserRepository
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.micronaut.http.HttpStatus
import io.micronaut.http.exceptions.HttpStatusException
import io.micronaut.scheduling.TaskExecutors
import io.micronaut.scheduling.annotation.Scheduled
import io.micronaut.security.authentication.Authentication
import jakarta.inject.Named
import jakarta.inject.Singleton
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import jakarta.transaction.Transactional.TxType
import org.slf4j.LoggerFactory
import reactor.core.publisher.Flux
import reactor.core.publisher.Sinks
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Feature 088 — AI-Assisted Risk Assessment Answers.
 *
 * Orchestrates per-requirement AI suggestion runs. Mirrors ExportJobService:
 * background work runs on the IO executor, each step has its own
 * REQUIRES_NEW transaction so we don't fight the closed HTTP-request
 * transaction, and the job heartbeats so a stale watchdog can clean up.
 *
 * Job lifecycle: QUEUED → RUNNING → (COMPLETED | FAILED | CANCELLED).
 */
@Singleton
open class AiSuggestionJobService(
    private val config: AiRiskAssessmentConfig,
    private val complianceAssistantService: ComplianceAssistantService,
    private val contextBuilder: AssessmentContextBuilder,
    private val aiJobRepository: AiSuggestionJobRepository,
    private val aiSuggestionRepository: AiAnswerSuggestionRepository,
    private val responseRepository: ResponseRepository,
    private val requirementRepository: RequirementRepository,
    private val userRepository: UserRepository,
    private val objectMapper: ObjectMapper,
    private val entityManager: EntityManager,
    @Named(TaskExecutors.IO) private val ioExecutor: ExecutorService
) {
    private val log = LoggerFactory.getLogger(AiSuggestionJobService::class.java)

    private companion object {
        const val HEARTBEAT_STALE_MIN = 30L
    }

    /**
     * Per-job future bag, so cancelJob can call .cancel(true) on them.
     * Cleared on terminal transition.
     */
    private val runningFutures = ConcurrentHashMap<Long, MutableList<Future<*>>>()
    private val cancelFlags = ConcurrentHashMap<Long, AtomicBoolean>()

    /**
     * Per-job SSE event sink. Subscribers (the SSE controller) receive
     * one event per completed requirement plus a terminal event when the
     * job ends. Sink is removed shortly after the terminal event so we
     * don't leak memory if the client disconnects late.
     */
    private val progressSinks = ConcurrentHashMap<Long, Sinks.Many<JobProgressEvent>>()

    /**
     * Validates and starts an AI pre-fill job. Returns the persisted jobId so
     * the controller can hand it back to the caller before any HTTP calls
     * have run.
     *
     * Synchronous validation lives in this REQUIRED transaction; the actual
     * per-requirement work is dispatched in [startBackground].
     */
    @Transactional
    open fun startJob(
        assessment: RiskAssessment,
        request: StartAiJobRequest,
        authentication: Authentication
    ): StartAiJobResponse {
        if (!complianceAssistantService.isEnabled()) {
            throw HttpStatusException(HttpStatus.FORBIDDEN, "AI risk assessment is disabled")
        }
        val scope = parseScope(request.scope)
        val triggeredBy = userRepository.findByUsername(authentication.name).orElse(null)
            ?: throw HttpStatusException(HttpStatus.FORBIDDEN, "Unknown user")

        // Concurrency guard — per-assessment AND global.
        val activeStatuses = listOf(AiSuggestionJobStatus.QUEUED, AiSuggestionJobStatus.RUNNING)
        autoResetStaleJobs()
        val perAssessment = aiJobRepository.findByRiskAssessmentIdAndStatusIn(assessment.id!!, activeStatuses)
        if (perAssessment.isNotEmpty()) {
            throw HttpStatusException(
                HttpStatus.CONFLICT,
                "An AI pre-fill job is already running for this assessment (id=${perAssessment.first().id})"
            )
        }
        val globalActive = aiJobRepository.countByStatusIn(activeStatuses)
        if (globalActive >= config.maxConcurrentJobsGlobal) {
            throw HttpStatusException(
                HttpStatus.TOO_MANY_REQUESTS,
                "Server is at the AI-job concurrency limit (${config.maxConcurrentJobsGlobal}). Try again shortly."
            )
        }

        // Build the target set of requirements.
        val allRequirements = requirementsForAssessment(assessment)
        val targets = filterTargets(assessment, allRequirements, scope, request.requirementIds, request.force)
        if (targets.isEmpty()) {
            throw HttpStatusException(HttpStatus.BAD_REQUEST, "No matching requirements to pre-fill")
        }

        val estimated = complianceAssistantService.estimateCostUsd(targets.size)
        if (estimated.toDouble() > config.maxCostPerJobUsd) {
            throw HttpStatusException(
                HttpStatus.REQUEST_ENTITY_TOO_LARGE,
                "Projected cost \$$estimated exceeds per-job cap of \$${config.maxCostPerJobUsd}"
            )
        }

        val job = AiSuggestionJob(
            riskAssessmentId = assessment.id!!,
            triggeredByUserId = triggeredBy.id!!,
            model = complianceAssistantService.currentModel,
            scope = scope,
            status = AiSuggestionJobStatus.QUEUED,
            totalCount = targets.size,
            estimatedCostUsd = estimated,
            createdAt = LocalDateTime.now()
        )
        val saved = aiJobRepository.save(job)
        entityManager.flush()
        log.info("AI job {} created: assessment={}, requirements={}, est=\${}", saved.id, assessment.id, targets.size, estimated)

        cancelFlags[saved.id!!] = AtomicBoolean(false)
        runningFutures[saved.id!!] = mutableListOf()
        progressSinks[saved.id!!] = Sinks.many().multicast().onBackpressureBuffer()

        // Capture only what the background needs (IDs) — no entity references
        // crossing the transaction boundary.
        val targetIds = targets.mapNotNull { it.id }
        ioExecutor.submit {
            runJobInBackground(saved.id!!, targetIds, assessment.id!!, triggeredBy.id!!)
        }
        return StartAiJobResponse(saved.id!!, targets.size, estimated)
    }

    /**
     * Top-level background runner. Catches everything and marks FAILED on
     * any uncaught exception. Per-requirement failures are absorbed locally.
     */
    private fun runJobInBackground(jobId: Long, requirementIds: List<Long>, assessmentId: Long, userId: Long) {
        val originalName = Thread.currentThread().name
        Thread.currentThread().name = "ai-job-$jobId"
        try {
            markRunning(jobId)
            val ctx = buildContext(assessmentId)
            val flag = cancelFlags[jobId] ?: AtomicBoolean(false)

            for (reqId in requirementIds) {
                if (flag.get()) {
                    log.info("AI job {} cancelled — stopping at requirement {}", jobId, reqId)
                    break
                }
                if (isJobCostCapExceeded(jobId)) {
                    log.warn("AI job {} cost cap exceeded — aborting remaining requirements", jobId)
                    markFailed(jobId, "cost-cap exceeded ($${config.maxCostPerJobUsd})")
                    return
                }
                processOneRequirement(jobId, reqId, assessmentId, userId, ctx)
            }
            finalize(jobId)
        } catch (e: Exception) {
            log.error("AI job {} crashed", jobId, e)
            try { markFailed(jobId, e.message?.take(2000) ?: "Unknown error") } catch (_: Exception) {}
        } finally {
            runningFutures.remove(jobId)
            cancelFlags.remove(jobId)
            Thread.currentThread().name = originalName
        }
    }

    /**
     * Per-requirement worker. One blocking ComplianceAssistantService call,
     * then write Response + AiAnswerSuggestion in a fresh transaction.
     */
    private fun processOneRequirement(
        jobId: Long,
        requirementId: Long,
        assessmentId: Long,
        userId: Long,
        ctx: AssessmentContext
    ) {
        val req = loadRequirement(requirementId) ?: run {
            log.warn("AI job {}: requirement {} not found — skipping", jobId, requirementId)
            incrementFailed(jobId, "Requirement $requirementId missing")
            return
        }
        try {
            val result = complianceAssistantService.suggest(req, ctx).get()
            applySuccess(jobId, assessmentId, requirementId, userId, result)
        } catch (e: Exception) {
            val cause = e.cause ?: e
            log.warn("AI job {} req {} failed: {}", jobId, requirementId, cause.message)
            applyFailure(jobId, assessmentId, requirementId, cause.message?.take(2000) ?: "AI call failed")
        }
    }

    // --- Transactional helpers (each opens a fresh tx, mirrors ExportJobService) ---

    @Transactional(TxType.REQUIRES_NEW)
    open fun loadRequirement(id: Long): Requirement? =
        requirementRepository.findById(id).orElse(null)

    @Transactional(TxType.REQUIRES_NEW)
    open fun markRunning(jobId: Long) {
        val j = aiJobRepository.findById(jobId).orElse(null) ?: return
        j.status = AiSuggestionJobStatus.RUNNING
        j.startedAt = LocalDateTime.now()
        j.lastHeartbeatAt = LocalDateTime.now()
        aiJobRepository.update(j)
    }

    @Transactional(TxType.REQUIRES_NEW)
    open fun markFailed(jobId: Long, errorMessage: String) {
        val j = aiJobRepository.findById(jobId).orElse(null) ?: return
        j.status = AiSuggestionJobStatus.FAILED
        j.errorMessage = errorMessage
        j.finishedAt = LocalDateTime.now()
        aiJobRepository.update(j)
        emitTerminal(j, "FAILED", errorMessage)
    }

    @Transactional(TxType.REQUIRES_NEW)
    open fun incrementFailed(jobId: Long, errorMessage: String? = null) {
        val j = aiJobRepository.findById(jobId).orElse(null) ?: return
        j.failedCount++
        j.lastHeartbeatAt = LocalDateTime.now()
        if (errorMessage != null && j.errorMessage.isNullOrBlank()) j.errorMessage = errorMessage
        aiJobRepository.update(j)
    }

    @Transactional(TxType.REQUIRES_NEW)
    open fun isJobCostCapExceeded(jobId: Long): Boolean {
        val j = aiJobRepository.findById(jobId).orElse(null) ?: return false
        return j.totalCostUsd.toDouble() > config.maxCostPerJobUsd
    }

    @Transactional(TxType.REQUIRES_NEW)
    open fun finalize(jobId: Long) {
        val j = aiJobRepository.findById(jobId).orElse(null) ?: return
        if (j.status.isTerminal()) return
        j.status = AiSuggestionJobStatus.COMPLETED
        j.finishedAt = LocalDateTime.now()
        aiJobRepository.update(j)
        emitTerminal(j, "COMPLETED", null)
    }

    /**
     * The success path: persist a new APPLIED suggestion (superseding any
     * existing APPLIED for this requirement) and upsert a Response row.
     */
    @Transactional(TxType.REQUIRES_NEW)
    open fun applySuccess(
        jobId: Long,
        assessmentId: Long,
        requirementId: Long,
        userId: Long,
        result: SuggestionResult
    ) {
        // 1) supersede any prior APPLIED row for this (assessment, requirement)
        aiSuggestionRepository.markAppliedAsSuperseded(assessmentId, requirementId)

        // 2) persist the new APPLIED suggestion
        val citationsJson = if (result.citations.isNotEmpty())
            objectMapper.writeValueAsString(result.citations)
        else null
        val suggestion = AiAnswerSuggestion(
            jobId = jobId,
            riskAssessmentId = assessmentId,
            requirementId = requirementId,
            suggestedAnswerType = result.answer,
            suggestedComment = null,    // rationale-as-comment handled by Response side
            rawConfidence = result.rawConfidence,
            confidenceBand = result.confidenceBand,
            rationale = result.rationale,
            citationsJson = citationsJson,
            model = result.model,
            promptVersion = result.promptVersion,
            inputTokens = result.inputTokens,
            outputTokens = result.outputTokens,
            costUsd = result.costUsd,
            webSearchUsed = result.webSearchUsed,
            status = AiAnswerSuggestionStatus.APPLIED
        )
        val savedSuggestion = aiSuggestionRepository.save(suggestion)

        // 3) accumulate cost on the job and bump completion counter
        val j = aiJobRepository.findById(jobId).orElse(null)
        if (j != null) {
            result.costUsd?.let { j.totalCostUsd = j.totalCostUsd.add(it).setScale(6, RoundingMode.HALF_UP) }
            j.completedCount++
            j.lastHeartbeatAt = LocalDateTime.now()
            aiJobRepository.update(j)
            emitProgress(j, requirementId, result.confidenceBand)
        }

        // 4) upsert the draft Response. UNKNOWN answer → no Response row
        //    (the human must answer manually).
        val answerType = result.answer.toAnswerType() ?: return
        val existing = responseRepository.findByRiskAssessmentIdAndRequirementId(assessmentId, requirementId)
        if (existing == null) {
            val assessment = entityManager.getReference(RiskAssessment::class.java, assessmentId)
            val requirement = entityManager.getReference(Requirement::class.java, requirementId)
            val response = Response(
                answerType = answerType,
                comment = result.rationale,
                respondentEmail = userRepository.findById(userId).orElse(null)?.email,
                source = ResponseSource.AI_GENERATED,
                aiSuggestionId = savedSuggestion.id,
                riskAssessment = assessment,
                requirement = requirement
            )
            responseRepository.save(response)
        } else {
            // Existing row is human (MANUAL) or already AI-touched. If MANUAL,
            // leave it alone — we never overwrite human work. If AI_GENERATED
            // (re-run), refresh in place. AI_EDITED rows are excluded upstream
            // by the re-run guard.
            if (existing.source == ResponseSource.AI_GENERATED || existing.source == ResponseSource.AI_EDITED) {
                existing.answerType = answerType
                existing.comment = result.rationale
                existing.source = ResponseSource.AI_GENERATED
                existing.aiSuggestionId = savedSuggestion.id
                responseRepository.update(existing)
            }
        }
    }

    /**
     * The failure path: persist a FAILED suggestion (no Response written)
     * and bump the job's failed counter.
     */
    @Transactional(TxType.REQUIRES_NEW)
    open fun applyFailure(
        jobId: Long,
        assessmentId: Long,
        requirementId: Long,
        errorMessage: String
    ) {
        aiSuggestionRepository.save(AiAnswerSuggestion(
            jobId = jobId,
            riskAssessmentId = assessmentId,
            requirementId = requirementId,
            suggestedAnswerType = SuggestedAnswerType.UNKNOWN,
            rawConfidence = 0.0,
            confidenceBand = com.secman.domain.ConfidenceBand.LOW,
            rationale = null,
            citationsJson = null,
            model = complianceAssistantService.currentModel,
            promptVersion = complianceAssistantService.currentPromptVersion,
            webSearchUsed = false,
            status = AiAnswerSuggestionStatus.FAILED,
            errorMessage = errorMessage
        ))
        val j = aiJobRepository.findById(jobId).orElse(null)
        if (j != null) {
            j.failedCount++
            j.lastHeartbeatAt = LocalDateTime.now()
            aiJobRepository.update(j)
            emitProgress(j, requirementId, com.secman.domain.ConfidenceBand.LOW)
        }
    }

    // --- Query / control surface (called from controller) -------------------

    @Transactional
    open fun getJobStatus(jobId: Long): AiJobStatusDto? {
        val j = aiJobRepository.findById(jobId).orElse(null) ?: return null
        return AiJobStatusDto(
            id = j.id!!, status = j.status, model = j.model, scope = j.scope.name,
            totalCount = j.totalCount, completedCount = j.completedCount, failedCount = j.failedCount,
            progressPercent = j.progressPercent(),
            totalCostUsd = j.totalCostUsd, estimatedCostUsd = j.estimatedCostUsd,
            startedAt = j.startedAt, finishedAt = j.finishedAt, errorMessage = j.errorMessage
        )
    }

    @Transactional
    open fun listAppliedSuggestions(assessmentId: Long): List<AppliedSuggestionDto> {
        val rows = aiSuggestionRepository.findByRiskAssessmentIdAndStatus(assessmentId, AiAnswerSuggestionStatus.APPLIED)
        return rows.map { s ->
            val citations: List<Citation> = s.citationsJson?.let { json ->
                runCatching { objectMapper.readValue<List<Citation>>(json) }.getOrElse {
                    log.warn("Bad citations JSON on suggestion {}: {}", s.id, it.message)
                    emptyList()
                }
            } ?: emptyList()
            AppliedSuggestionDto(
                requirementId = s.requirementId,
                suggestedAnswerType = s.suggestedAnswerType,
                suggestedComment = s.suggestedComment,
                rawConfidence = s.rawConfidence,
                confidenceBand = s.confidenceBand,
                rationale = s.rationale,
                citations = citations,
                model = s.model,
                promptVersion = s.promptVersion,
                webSearchUsed = s.webSearchUsed,
                createdAt = s.createdAt
            )
        }
    }

    @Transactional
    open fun cancelJob(jobId: Long): Boolean {
        val j = aiJobRepository.findById(jobId).orElse(null) ?: return false
        if (j.status.isTerminal()) return false
        j.status = AiSuggestionJobStatus.CANCELLED
        j.finishedAt = LocalDateTime.now()
        aiJobRepository.update(j)
        cancelFlags[jobId]?.set(true)
        runningFutures[jobId]?.forEach { it.cancel(true) }
        emitTerminal(j, "CANCELLED", "Cancelled by user")
        log.info("AI job {} cancelled by user", jobId)
        return true
    }

    /**
     * Reactor Flux exposing per-requirement progress + a terminal event for
     * a single job. Returns an empty stream if the job is unknown or already
     * terminal (the controller follows up with the cached job status).
     */
    fun getProgressStream(jobId: Long): Flux<JobProgressEvent> {
        val sink = progressSinks[jobId] ?: return Flux.empty()
        return sink.asFlux()
    }

    @Transactional
    open fun clearLowConfidence(assessmentId: Long): Long {
        return responseRepository.deleteLowConfidenceAiResponses(assessmentId)
    }

    // --- Scheduled cleanup --------------------------------------------------

    /**
     * Mark runaway jobs as FAILED. Mirrors ExportJobService's autoReset.
     * Runs hourly; safe to run more often (idempotent).
     */
    @Scheduled(fixedDelay = "1h", initialDelay = "5m")
    open fun reclaimStaleJobs() {
        val cutoff = LocalDateTime.now().minusMinutes(HEARTBEAT_STALE_MIN)
        val stale = aiJobRepository.findStaleByStatus(
            listOf(AiSuggestionJobStatus.QUEUED, AiSuggestionJobStatus.RUNNING),
            cutoff
        )
        if (stale.isEmpty()) return
        stale.forEach { j ->
            log.warn("Reclaiming stale AI job {}: lastHeartbeat={}, createdAt={}", j.id, j.lastHeartbeatAt, j.createdAt)
            try {
                markFailed(j.id!!, "Reclaimed by watchdog: no heartbeat for >${HEARTBEAT_STALE_MIN}min")
            } catch (e: Exception) {
                log.error("Failed to reclaim stale AI job {}", j.id, e)
            }
        }
    }

    private fun autoResetStaleJobs() {
        try { reclaimStaleJobs() } catch (e: Exception) {
            log.debug("autoResetStaleJobs swallowed: {}", e.message)
        }
    }

    // --- Internals ----------------------------------------------------------

    private fun parseScope(raw: String): AiSuggestionScope =
        try { AiSuggestionScope.valueOf(raw) }
        catch (_: Exception) { throw HttpStatusException(HttpStatus.BAD_REQUEST, "Unknown scope: $raw") }

    private fun requirementsForAssessment(assessment: RiskAssessment): List<Requirement> {
        // Same fallback ladder as ResponseController.getRequirementsForAssessment.
        if (assessment.useCases.isNotEmpty()) {
            val direct = assessment.useCases.flatMap {
                requirementRepository.findByUsecaseId(it.id!!)
            }.distinct()
            if (direct.isNotEmpty()) return direct
            val standardLinked = entityManager.createQuery(
                """
                SELECT DISTINCT r FROM Requirement r
                JOIN r.useCases u
                JOIN u.standards s
                JOIN s.useCases uc
                WHERE uc.id IN :ids
                """,
                Requirement::class.java
            ).setParameter("ids", assessment.useCases.map { it.id }).resultList
            if (standardLinked.isNotEmpty()) return standardLinked
        }
        return requirementRepository.findAll()
    }

    private fun filterTargets(
        assessment: RiskAssessment,
        all: List<Requirement>,
        scope: AiSuggestionScope,
        explicitIds: List<Long>?,
        force: Boolean
    ): List<Requirement> {
        val base = when (scope) {
            AiSuggestionScope.WHOLE_ASSESSMENT -> all
            AiSuggestionScope.SUBSET -> {
                val wanted = explicitIds?.toSet() ?: emptySet()
                all.filter { it.id in wanted }
            }
            AiSuggestionScope.SINGLE_REQUIREMENT -> {
                val target = explicitIds?.firstOrNull() ?: throw HttpStatusException(
                    HttpStatus.BAD_REQUEST, "SINGLE_REQUIREMENT scope requires a requirementId"
                )
                all.filter { it.id == target }
            }
        }
        if (force) return base
        // Re-run guard: skip requirements whose Response is AI_EDITED.
        val assessmentId = assessment.id!!
        return base.filter { req ->
            req.id?.let {
                !responseRepository.existsByAssessmentRequirementAndSource(assessmentId, it, ResponseSource.AI_EDITED)
            } ?: false
        }
    }

    private fun buildContext(assessmentId: Long): AssessmentContext {
        // Need a fresh tx for entity loading from the background thread.
        return loadAssessmentForContext(assessmentId)
    }

    @Transactional(TxType.REQUIRES_NEW)
    open fun loadAssessmentForContext(assessmentId: Long): AssessmentContext {
        val a = entityManager.find(RiskAssessment::class.java, assessmentId)
            ?: throw HttpStatusException(HttpStatus.NOT_FOUND, "Assessment $assessmentId not found")
        return contextBuilder.build(a)
    }

    // --- SSE emit helpers ---------------------------------------------------

    private fun emitProgress(
        job: AiSuggestionJob,
        requirementId: Long,
        band: com.secman.domain.ConfidenceBand
    ) {
        val sink = progressSinks[job.id] ?: return
        sink.tryEmitNext(
            JobProgressEvent(
                jobId = job.id!!,
                type = "PROGRESS",
                requirementId = requirementId,
                band = band,
                completedCount = job.completedCount,
                failedCount = job.failedCount,
                totalCount = job.totalCount,
                totalCostUsd = job.totalCostUsd
            )
        )
    }

    private fun emitTerminal(job: AiSuggestionJob, type: String, errorMessage: String?) {
        val sink = progressSinks[job.id] ?: return
        sink.tryEmitNext(
            JobProgressEvent(
                jobId = job.id!!,
                type = type,
                requirementId = null,
                band = null,
                completedCount = job.completedCount,
                failedCount = job.failedCount,
                totalCount = job.totalCount,
                totalCostUsd = job.totalCostUsd,
                errorMessage = errorMessage
            )
        )
        sink.tryEmitComplete()
        // Best-effort cleanup; if a slow SSE client subscribes after the
        // terminal event, getProgressStream returns Flux.empty() and the
        // client falls back to a single GET /jobs/{jobId} to see final state.
        progressSinks.remove(job.id)
    }
}
