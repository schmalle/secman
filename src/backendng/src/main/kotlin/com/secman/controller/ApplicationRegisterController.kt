package com.secman.controller

import com.secman.dto.ApplicationRegisterAssetUpdateRequest
import com.secman.dto.ApplicationRegisterDetail
import com.secman.dto.ApplicationRegisterRequest
import com.secman.dto.ApplicationRegisterSummary
import com.secman.service.ApplicationRegisterExportService
import com.secman.service.ApplicationRegisterService
import io.micronaut.http.HttpResponse
import io.micronaut.http.annotation.Body
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Delete
import io.micronaut.http.annotation.Get
import io.micronaut.http.annotation.PathVariable
import io.micronaut.http.annotation.Post
import io.micronaut.http.annotation.Put
import io.micronaut.http.annotation.QueryValue
import io.micronaut.scheduling.TaskExecutors
import io.micronaut.scheduling.annotation.ExecuteOn
import io.micronaut.security.annotation.Secured
import io.micronaut.security.authentication.Authentication
import io.micronaut.security.rules.SecurityRule
import java.net.HttpURLConnection
import java.net.URI
import java.time.LocalDate

@Controller("/api/applications")
open class ApplicationRegisterController(
    private val service: ApplicationRegisterService,
    private val exportService: ApplicationRegisterExportService
) {

    @Get
    @Secured(SecurityRule.IS_AUTHENTICATED)
    open fun list(@QueryValue(defaultValue = "") search: String?): HttpResponse<List<ApplicationRegisterSummary>> {
        return HttpResponse.ok(service.list(search))
    }

    @Get("/{id}")
    @Secured(SecurityRule.IS_AUTHENTICATED)
    open fun get(@PathVariable id: Long): HttpResponse<ApplicationRegisterDetail> {
        return try {
            HttpResponse.ok(service.get(id))
        } catch (e: NoSuchElementException) {
            HttpResponse.notFound()
        }
    }

    @Post
    @Secured("ADMIN", "SECCHAMPION")
    open fun create(
        @Body request: ApplicationRegisterRequest,
        authentication: Authentication
    ): HttpResponse<Any> {
        return try {
            HttpResponse.created(service.create(request, authentication.name))
        } catch (e: IllegalArgumentException) {
            HttpResponse.badRequest(mapOf("error" to e.message))
        }
    }

    @Put("/{id}")
    @Secured("ADMIN", "SECCHAMPION")
    open fun update(
        @PathVariable id: Long,
        @Body request: ApplicationRegisterRequest,
        authentication: Authentication
    ): HttpResponse<Any> {
        return try {
            HttpResponse.ok(service.update(id, request, authentication.name))
        } catch (e: NoSuchElementException) {
            HttpResponse.notFound()
        } catch (e: IllegalArgumentException) {
            HttpResponse.badRequest(mapOf("error" to e.message))
        }
    }

    @Delete("/{id}")
    @Secured("ADMIN", "SECCHAMPION")
    open fun delete(@PathVariable id: Long): HttpResponse<Void> {
        return try {
            service.delete(id)
            HttpResponse.noContent()
        } catch (e: NoSuchElementException) {
            HttpResponse.notFound()
        }
    }

    @Put("/{id}/assets")
    @Secured("ADMIN", "SECCHAMPION")
    open fun replaceAssets(
        @PathVariable id: Long,
        @Body request: ApplicationRegisterAssetUpdateRequest,
        authentication: Authentication
    ): HttpResponse<Any> {
        return try {
            HttpResponse.ok(service.replaceAssets(id, request.assetIds, authentication.name))
        } catch (e: NoSuchElementException) {
            HttpResponse.notFound()
        } catch (e: IllegalArgumentException) {
            HttpResponse.badRequest(mapOf("error" to e.message))
        }
    }

    @Get("/check-github-url")
    @Secured(SecurityRule.IS_AUTHENTICATED)
    @ExecuteOn(TaskExecutors.BLOCKING)
    open fun checkGithubUrl(@QueryValue url: String): HttpResponse<Map<String, Any>> {
        val trimmed = url.trim()
        if (trimmed.isBlank()) {
            return HttpResponse.badRequest(mapOf("error" to "URL is required"))
        }
        return try {
            val uri = URI.create(trimmed)
            if (uri.scheme != "https" && uri.scheme != "http") {
                return HttpResponse.ok(mapOf("reachable" to false, "reason" to "URL must use http or https"))
            }
            // SECURITY: This endpoint is reachable by any authenticated user and was
            // previously an open SSRF proxy — it would issue a server-side HTTP request
            // to ANY attacker-supplied host (including cloud metadata endpoints like
            // 169.254.169.254 and internal-only services), returning reachability/status
            // as an oracle. Since this check exists only to validate GitHub repository
            // URLs, restrict the target host to github.com.
            val host = uri.host?.lowercase()
            if (host != "github.com" && host != "www.github.com") {
                return HttpResponse.ok(mapOf("reachable" to false, "reason" to "Only github.com URLs are supported"))
            }
            val connection = uri.toURL().openConnection() as HttpURLConnection
            connection.requestMethod = "HEAD"
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            // Do not follow redirects server-side: a redirect target is outside our
            // host allowlist check and would reopen the SSRF this fix closes.
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("User-Agent", "SecMan/1.0")
            val status = connection.responseCode
            connection.disconnect()
            HttpResponse.ok(mapOf("reachable" to (status in 200..399), "statusCode" to status))
        } catch (e: IllegalArgumentException) {
            HttpResponse.ok(mapOf("reachable" to false, "reason" to "Invalid URL format"))
        } catch (e: Exception) {
            HttpResponse.ok(mapOf("reachable" to false, "reason" to (e.message ?: "Unreachable")))
        }
    }

    @Get("/export")
    @Secured(SecurityRule.IS_AUTHENTICATED)
    @ExecuteOn(TaskExecutors.BLOCKING)
    open fun exportApplications(): HttpResponse<*> {
        return try {
            val applications = exportService.exportApplications()
            if (applications.isEmpty()) {
                return HttpResponse.badRequest(mapOf("error" to "No applications available to export"))
            }

            val outputStream = exportService.writeToExcel(applications)
            val filename = "applications_export_${LocalDate.now()}.xlsx"

            HttpResponse.ok(outputStream.toByteArray())
                .header("Content-Type", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                .header("Content-Disposition", "attachment; filename=\"$filename\"")
        } catch (e: Exception) {
            HttpResponse.serverError(mapOf("error" to "An internal error occurred"))
        }
    }
}
