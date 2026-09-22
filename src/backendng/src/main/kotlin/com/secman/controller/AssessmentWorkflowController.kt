package com.secman.controller

import com.secman.service.AssessmentWorkflowService
import io.micronaut.http.annotation.*
import io.micronaut.scheduling.TaskExecutors
import io.micronaut.scheduling.annotation.ExecuteOn
import io.micronaut.security.annotation.Secured
import io.micronaut.security.authentication.Authentication
import io.micronaut.security.rules.SecurityRule
import io.micronaut.serde.annotation.Serdeable

/** Human review actions are intentionally absent from the MCP tool registry. */
@Controller("/api/risk-assessments/{id}/workflow")
@Secured(SecurityRule.IS_AUTHENTICATED)
@ExecuteOn(TaskExecutors.BLOCKING)
class AssessmentWorkflowController(private val workflow: AssessmentWorkflowService) {
    @Serdeable data class AssignmentRequest(val userId: Long?, val email: String = "", val role: String, val requirementIds: Set<Long> = emptySet())
    @Serdeable data class AcceptanceRequest(val answerRevision: Long, val rationale: String)

    @Get("/assignments")
    @Secured("ADMIN", "SECCHAMPION")
    fun assignments(id: Long, authentication: Authentication) = workflow.listAssignments(id, authentication)

    @Post("/assignments")
    @Secured("ADMIN", "SECCHAMPION")
    fun assign(id: Long, @Body request: AssignmentRequest, authentication: Authentication) =
        workflow.assign(id, authentication, request.userId, request.email, request.role, request.requirementIds)

    @Delete("/assignments/{assignmentId}")
    @Secured("ADMIN", "SECCHAMPION")
    fun revoke(id: Long, assignmentId: Long, authentication: Authentication) = workflow.revoke(id, assignmentId, authentication)

    @Post("/submit")
    fun submit(id: Long, authentication: Authentication) = workflow.submit(id, authentication).let {
        mapOf("id" to it.id, "status" to it.status)
    }

    @Post("/reopen")
    @Secured("ADMIN", "SECCHAMPION")
    fun reopen(id: Long, authentication: Authentication) = workflow.reopen(id, authentication).let {
        mapOf("id" to it.id, "status" to it.status)
    }

    @Post("/accept")
    fun accept(id: Long, @Body request: AcceptanceRequest, authentication: Authentication) =
        workflow.accept(id, authentication, request.answerRevision, request.rationale)
}
