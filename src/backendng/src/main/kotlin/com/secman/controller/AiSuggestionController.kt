package com.secman.controller

import com.secman.dto.AiJobStatusDto
import com.secman.dto.AppliedSuggestionDto
import com.secman.dto.ClearLowConfidenceResponse
import com.secman.dto.StartAiJobRequest
import com.secman.dto.StartAiJobResponse
import com.secman.service.AiSuggestionJobService
import com.secman.service.AssessmentOwnershipGuard
import io.micronaut.http.HttpResponse
import io.micronaut.http.HttpStatus
import io.micronaut.http.annotation.Body
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Delete
import io.micronaut.http.annotation.Get
import io.micronaut.http.annotation.PathVariable
import io.micronaut.http.annotation.Post
import io.micronaut.http.exceptions.HttpStatusException
import io.micronaut.scheduling.TaskExecutors
import io.micronaut.scheduling.annotation.ExecuteOn
import io.micronaut.security.annotation.Secured
import io.micronaut.security.authentication.Authentication
import io.micronaut.serde.annotation.Serdeable
import org.slf4j.LoggerFactory

/**
 * Feature 088 — AI-Assisted Risk Assessment Answers.
 *
 * All endpoints @Secured ADMIN,SECCHAMPION AND the AssessmentOwnershipGuard
 * verifies the caller is the assessor/requestor (or ADMIN). RISK / USER /
 * any other role gets 403 from Micronaut's role check before the guard runs.
 */
@Controller("/api/risk-assessments/{id}/ai-suggestions")
@Secured("ADMIN", "SECCHAMPION")
@ExecuteOn(TaskExecutors.BLOCKING)
open class AiSuggestionController(
    private val jobService: AiSuggestionJobService,
    private val ownershipGuard: AssessmentOwnershipGuard
) {
    private val log = LoggerFactory.getLogger(AiSuggestionController::class.java)

    @Post("/jobs")
    open fun startJob(
        @PathVariable id: Long,
        @Body request: StartAiJobRequest,
        authentication: Authentication
    ): HttpResponse<StartAiJobResponse> {
        val assessment = ownershipGuard.check(id, authentication)
        val resp = jobService.startJob(assessment, request, authentication)
        log.info("AI job {} accepted for assessment {} by user {}", resp.jobId, id, authentication.name)
        return HttpResponse.status<StartAiJobResponse>(HttpStatus.CREATED).body(resp)
    }

    @Get("/jobs/{jobId}")
    open fun getJob(
        @PathVariable id: Long,
        @PathVariable jobId: Long,
        authentication: Authentication
    ): HttpResponse<AiJobStatusDto> {
        ownershipGuard.check(id, authentication)
        val status = jobService.getJobStatus(jobId)
            ?: throw HttpStatusException(HttpStatus.NOT_FOUND, "AI job $jobId not found")
        return HttpResponse.ok(status)
    }

    @Delete("/jobs/{jobId}")
    open fun cancelJob(
        @PathVariable id: Long,
        @PathVariable jobId: Long,
        authentication: Authentication
    ): HttpResponse<Void> {
        ownershipGuard.check(id, authentication)
        val cancelled = jobService.cancelJob(jobId)
        return if (cancelled) HttpResponse.noContent()
        else HttpResponse.status(HttpStatus.CONFLICT)
    }

    @Get
    open fun listApplied(
        @PathVariable id: Long,
        authentication: Authentication
    ): HttpResponse<List<AppliedSuggestionDto>> {
        ownershipGuard.check(id, authentication)
        return HttpResponse.ok(jobService.listAppliedSuggestions(id))
    }

    @Post("/clear-low-confidence")
    open fun clearLowConfidence(
        @PathVariable id: Long,
        authentication: Authentication
    ): HttpResponse<ClearLowConfidenceResponse> {
        ownershipGuard.check(id, authentication)
        val deleted = jobService.clearLowConfidence(id)
        log.info("Cleared {} low-confidence AI responses for assessment {} (user={})", deleted, id, authentication.name)
        return HttpResponse.ok(ClearLowConfidenceResponse(deleted))
    }
}
