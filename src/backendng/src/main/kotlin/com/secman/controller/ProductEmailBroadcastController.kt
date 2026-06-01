package com.secman.controller

import com.secman.domain.EmailBroadcastJob
import com.secman.service.EmailBroadcastService
import io.micronaut.http.HttpResponse
import io.micronaut.http.annotation.Body
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
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

@Controller("/api/admin/email-broadcast")
@Secured("ADMIN", "SECCHAMPION")
@ExecuteOn(TaskExecutors.IO)
open class ProductEmailBroadcastController(
    private val emailBroadcastService: EmailBroadcastService
) {
    private val log = LoggerFactory.getLogger(ProductEmailBroadcastController::class.java)

    @Get("/product-recipients")
    open fun productRecipientCount(
        @QueryValue @NotBlank @Size(max = 255) productName: String
    ): HttpResponse<Map<String, Any>> =
        HttpResponse.ok(
            mapOf(
                "count" to emailBroadcastService.productRecipientCount(productName),
                "productName" to productName
            )
        )

    @Post("/product")
    open fun createProductBroadcast(
        @Body @Valid request: ProductBroadcastRequest,
        authentication: Authentication
    ): HttpResponse<EmailBroadcastJob> {
        val htmlText = request.htmlContent.trim()
        if (htmlText.isEmpty()) {
            return HttpResponse.badRequest()
        }

        val job = emailBroadcastService.createProductJob(
            subject = request.subject,
            htmlContent = htmlText,
            createdBy = authentication.name,
            productName = request.productName
        )

        if (job.totalRecipients == 0) {
            log.warn("Product broadcast {} created with 0 recipients for {}", job.id, request.productName)
        }

        emailBroadcastService.runJobAsync(job.id!!)
        log.info(
            "Product broadcast job {} kicked off by {} for {} recipients and product {}",
            job.id,
            authentication.name,
            job.totalRecipients,
            request.productName
        )
        return HttpResponse.created(job)
    }

    @Serdeable
    data class ProductBroadcastRequest(
        @field:NotBlank @field:Size(max = 255) val productName: String,
        @field:NotBlank @field:Size(max = 255) val subject: String,
        @field:NotBlank @field:Size(max = 1_000_000) val htmlContent: String
    )
}
