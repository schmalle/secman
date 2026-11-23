package com.secman.controller

import com.secman.dto.CreateSupplierInvitation
import com.secman.dto.SupplierCollectionStatus
import com.secman.service.SupplierAssessmentService
import com.secman.service.SupplierInvitationOutbox
import com.secman.service.SupplierSubmissionPollState
import io.micronaut.http.HttpResponse
import io.micronaut.http.annotation.*
import io.micronaut.scheduling.TaskExecutors
import io.micronaut.scheduling.annotation.ExecuteOn
import io.micronaut.security.annotation.Secured
import io.micronaut.security.authentication.Authentication
import io.micronaut.security.rules.SecurityRule
import jakarta.validation.Valid

@Controller("/api/risk-assessments/{id}/supplier-invitation")
@Secured(SecurityRule.IS_AUTHENTICATED)
@ExecuteOn(TaskExecutors.BLOCKING)
open class SupplierAssessmentInvitationController(
    private val suppliers: SupplierAssessmentService,
    private val outbox: SupplierInvitationOutbox,
    private val pollState: SupplierSubmissionPollState
) {
    @Post
    @Secured("ADMIN", "SECCHAMPION")
    open fun create(id: Long, @Body @Valid request: CreateSupplierInvitation, authentication: Authentication) =
        HttpResponse.created(suppliers.create(id, authentication, request))

    @Get
    @Secured("ADMIN", "SECCHAMPION")
    fun latest(id: Long, authentication: Authentication) = suppliers.latest(id, authentication)

    @Get("/status")
    @Secured(SecurityRule.IS_AUTHENTICATED)
    fun status(id: Long, authentication: Authentication): HttpResponse<SupplierCollectionStatus> =
        pollState.status(id, authentication)?.let { HttpResponse.ok(it) } ?: HttpResponse.noContent()

    @Post("/{invitationId}/check")
    @Secured("ADMIN", "SECCHAMPION")
    fun requestCheck(id: Long, invitationId: String, authentication: Authentication): HttpResponse<Any> {
        pollState.requestRetry(id, invitationId, authentication)
        return HttpResponse.accepted()
    }

    @Post("/{invitationId}/delivery")
    @Secured("ADMIN", "SECCHAMPION")
    fun enqueueDelivery(id: Long, invitationId: String, authentication: Authentication): HttpResponse<Any> {
        outbox.enqueue(id, invitationId, authentication)
        return HttpResponse.accepted()
    }

    @Post("/{invitationId}/incident-hold/{revision}")
    @Secured("ADMIN")
    fun placeIncidentHold(id: Long, invitationId: String, revision: Long, authentication: Authentication): HttpResponse<Any> {
        suppliers.placeIncidentHold(id, invitationId, revision, authentication)
        return HttpResponse.noContent()
    }

    @Delete("/{invitationId}")
    @Secured("ADMIN", "SECCHAMPION")
    fun revoke(id: Long, invitationId: String, authentication: Authentication): HttpResponse<Any> {
        suppliers.revoke(id, invitationId, authentication)
        return HttpResponse.noContent()
    }
}
