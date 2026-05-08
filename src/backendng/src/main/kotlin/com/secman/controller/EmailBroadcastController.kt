package com.secman.controller

import com.secman.domain.EmailBroadcastJob
import com.secman.service.EmailBroadcastService
import io.micronaut.http.HttpResponse
import io.micronaut.http.annotation.*
import io.micronaut.scheduling.TaskExecutors
import io.micronaut.scheduling.annotation.ExecuteOn
import io.micronaut.security.annotation.Secured
import io.micronaut.security.authentication.Authentication
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.slf4j.LoggerFactory

/**
 * Admin-only email broadcast.
 *
 * Endpoints:
 *  - GET  /api/admin/email-broadcast/recipients   -> { count }
 *  - POST /api/admin/email-broadcast              -> creates job, kicks off async send
 *  - GET  /api/admin/email-broadcast/jobs         -> recent jobs (newest first)
 *  - GET  /api/admin/email-broadcast/jobs/{id}    -> job status
 */
@Controller("/api/admin/email-broadcast")
@Secured("ADMIN")
@ExecuteOn(TaskExecutors.IO)
open class EmailBroadcastController(
    private val emailBroadcastService: EmailBroadcastService
) {
    private val log = LoggerFactory.getLogger(EmailBroadcastController::class.java)

    @Get("/recipients")
    open fun recipientCount(): HttpResponse<Map<String, Long>> =
        HttpResponse.ok(mapOf("count" to emailBroadcastService.recipientCount()))

    @Post
    open fun createBroadcast(
        @Body @Valid request: BroadcastRequest,
        authentication: Authentication
    ): HttpResponse<EmailBroadcastJob> {
        val htmlText = request.htmlContent.trim()
        if (htmlText.isEmpty()) {
            return HttpResponse.badRequest()
        }

        val job = emailBroadcastService.createJob(
            subject = request.subject,
            htmlContent = htmlText,
            createdBy = authentication.name
        )

        if (job.totalRecipients == 0) {
            log.warn("Broadcast {} created with 0 recipients", job.id)
        }

        emailBroadcastService.runJobAsync(job.id!!)
        log.info("Broadcast job {} kicked off by {} for {} recipients", job.id, authentication.name, job.totalRecipients)
        return HttpResponse.created(job)
    }

    @Get("/jobs")
    open fun listJobs(): HttpResponse<List<EmailBroadcastJob>> =
        HttpResponse.ok(emailBroadcastService.listRecentJobs())

    @Get("/jobs/{id}")
    open fun getJob(@PathVariable id: Long): HttpResponse<EmailBroadcastJob> {
        val job = emailBroadcastService.getJob(id) ?: return HttpResponse.notFound()
        return HttpResponse.ok(job)
    }

    @io.micronaut.serde.annotation.Serdeable
    data class BroadcastRequest(
        @field:NotBlank @field:Size(max = 255) val subject: String,
        @field:NotBlank @field:Size(max = 1_000_000) val htmlContent: String
    )
}
