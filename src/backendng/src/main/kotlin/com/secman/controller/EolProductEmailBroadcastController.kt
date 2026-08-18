package com.secman.controller

import com.secman.domain.EmailBroadcastJob
import com.secman.domain.EmailBroadcastTargetGroup
import com.secman.service.EmailBroadcastService
import io.micronaut.http.HttpResponse
import io.micronaut.http.annotation.Body
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.micronaut.http.annotation.PathVariable
import io.micronaut.http.annotation.Post
import io.micronaut.http.annotation.QueryValue
import io.micronaut.scheduling.TaskExecutors
import io.micronaut.scheduling.annotation.ExecuteOn
import io.micronaut.security.annotation.Secured
import io.micronaut.security.authentication.Authentication
import io.micronaut.serde.annotation.Serdeable
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.slf4j.LoggerFactory

/**
 * "Contact affected owners" feature for the EOL product drilldown page:
 * compose a message and mail every owner/creator/uploader/mapped-user of the
 * accessible systems a given EOL product is EOL or approaching EOL on.
 *
 * A distinct base path (rather than folding into [ProductEmailBroadcastController])
 * keeps the two "product" concepts apart: that controller's `productName` means
 * a vulnerable installed product, this one's means an EOL `EolFinding.componentName`
 * — different recipient resolution, different data source.
 *
 * Endpoints:
 *  - GET  /api/admin/email-broadcast/eol/product-recipients -> { count }
 *  - POST /api/admin/email-broadcast/eol/product             -> creates job, kicks off async send
 *  - GET  /api/admin/email-broadcast/eol/jobs/{id}           -> job status
 */
@Controller("/api/admin/email-broadcast/eol")
@Secured("ADMIN", "SECCHAMPION")
@ExecuteOn(TaskExecutors.IO)
open class EolProductEmailBroadcastController(
    private val emailBroadcastService: EmailBroadcastService
) {
    private val log = LoggerFactory.getLogger(EolProductEmailBroadcastController::class.java)

    @Get("/product-recipients")
    open fun productRecipientCount(
        @QueryValue @NotBlank @Size(max = 512) productName: String,
        authentication: Authentication
    ): HttpResponse<Map<String, Any>> =
        HttpResponse.ok(
            mapOf(
                "count" to emailBroadcastService.eolProductRecipientCount(productName, authentication),
                "productName" to productName
            )
        )

    @Post("/product")
    open fun createEolProductBroadcast(
        @Body @Valid request: EolProductBroadcastRequest,
        authentication: Authentication
    ): HttpResponse<*> {
        val htmlText = request.htmlContent.trim()
        if (htmlText.isEmpty()) {
            return HttpResponse.badRequest<EmailBroadcastJob>()
        }

        val ccRecipients = normalizeCcRecipients(request.ccRecipients)
            ?: return HttpResponse.badRequest(ErrorResponse("Invalid CC email address"))

        val job = emailBroadcastService.createEolProductJob(
            subject = request.subject,
            htmlContent = htmlText,
            createdBy = authentication.name,
            productName = request.productName,
            authentication = authentication,
            ccRecipients = ccRecipients
        )

        if (job.totalRecipients == 0) {
            log.warn("EOL product broadcast {} created with 0 recipients for {}", job.id, request.productName)
        }

        emailBroadcastService.runEolProductJobAsync(job.id!!, authentication)
        log.info(
            "EOL product broadcast job {} kicked off by {} for {} recipients and product {}",
            job.id,
            authentication.name,
            job.totalRecipients,
            request.productName
        )
        return HttpResponse.created(job)
    }

    /**
     * Scoped deliberately to jobs created by *this* controller: [EmailBroadcastService.getJob]
     * has no notion of caller — without the target-group check here, a SECCHAMPION
     * (who cannot reach the ADMIN-only [EmailBroadcastController]) could still read
     * an unrelated ALL_USERS/ADMINS_ONLY broadcast's subject and HTML body by id.
     * An id for a job of a different target group answers 404, identical to an
     * unknown id.
     */
    @Get("/jobs/{id}")
    open fun getJob(@PathVariable id: Long): HttpResponse<EmailBroadcastJob> {
        val job = emailBroadcastService.getJob(id)
            ?.takeIf { it.targetGroup == EmailBroadcastTargetGroup.EOL_PRODUCT_USERS }
            ?: return HttpResponse.notFound()
        return HttpResponse.ok(job)
    }

    /**
     * Normalizes manually-entered CC addresses: trims, drops blanks, dedupes
     * case-insensitively. Returns null if any surviving address fails the
     * format check, so the caller can 400 rather than silently drop it.
     */
    private fun normalizeCcRecipients(raw: List<String>): List<String>? {
        val deduped = raw.map { it.trim() }.filter { it.isNotBlank() }.distinctBy { it.lowercase() }
        if (deduped.any { it.length > 254 || !ccEmailRegex.matches(it) }) return null
        return deduped
    }

    @Serdeable
    data class ErrorResponse(val error: String)

    @Serdeable
    data class EolProductBroadcastRequest(
        @field:NotBlank @field:Size(max = 512) val productName: String,
        @field:NotBlank @field:Size(max = 255) val subject: String,
        @field:NotBlank @field:Size(max = 1_000_000) val htmlContent: String,
        @field:Size(max = 25) val ccRecipients: List<String> = emptyList()
    )

    companion object {
        private val ccEmailRegex = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+\$")
    }
}
