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

    // SECURITY: despite the name, this endpoint used to open a server-side HTTP connection
    // to ANY URL supplied by ANY authenticated user (down to plain USER role), with no host
    // restriction — a textbook SSRF that could be used to probe internal network services or
    // the cloud metadata endpoint (169.254.169.254) and read back reachability/status from the
    // backend's network position. Despite the name "check-github-url", the host was never
    // actually checked. Restrict to the github.com host family and refuse to leak redirect
    // targets to other hosts.
    private val allowedGithubHosts = setOf("github.com", "www.github.com", "api.github.com", "raw.githubusercontent.com")

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
            if (uri.scheme != "https") {
                return HttpResponse.ok(mapOf("reachable" to false, "reason" to "URL must use https"))
            }
            val host = uri.host?.lowercase()
            if (host == null || host !in allowedGithubHosts) {
                return HttpResponse.ok(mapOf("reachable" to false, "reason" to "URL must point to github.com"))
            }
            val connection = uri.toURL().openConnection() as HttpURLConnection
            connection.requestMethod = "HEAD"
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            // Do not auto-follow redirects: a github.com response could redirect to an
            // arbitrary internal host, which would defeat the host allowlist above.
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
