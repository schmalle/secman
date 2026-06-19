package com.secman.controller

import com.secman.dto.InstalledProductImportRequest
import com.secman.dto.InstalledProductListResponse
import com.secman.service.InstalledProductImportService
import com.secman.service.InstalledProductListService
import io.micronaut.core.annotation.Nullable
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
import jakarta.validation.Valid
import org.slf4j.LoggerFactory

@Controller("/api/installed-products")
@Secured("ADMIN", "VULN", "SECCHAMPION")
@ExecuteOn(TaskExecutors.BLOCKING)
open class InstalledProductController(
    private val installedProductImportService: InstalledProductImportService,
    private val installedProductListService: InstalledProductListService
) {
    private val log = LoggerFactory.getLogger(InstalledProductController::class.java)

    @Get
    open fun list(
        authentication: Authentication,
        @Nullable @QueryValue search: String?,
        @Nullable @QueryValue limit: Int?
    ): HttpResponse<InstalledProductListResponse> {
        return HttpResponse.ok(installedProductListService.list(authentication, search, limit))
    }

    @Get("/by-server")
    open fun listByServer(
        authentication: Authentication,
        @QueryValue server: String,
        @Nullable @QueryValue limit: Int?
    ): HttpResponse<*> {
        return try {
            HttpResponse.ok(installedProductListService.listForServer(authentication, server, limit))
        } catch (e: IllegalArgumentException) {
            HttpResponse.badRequest(mapOf("error" to (e.message ?: "Invalid request")))
        }
    }

    @Post("/import")
    @Secured("ADMIN", "VULN")
    open fun importProducts(
        @Body @Valid request: InstalledProductImportRequest,
        authentication: Authentication
    ): HttpResponse<*> {
        if (request.products.size > InstalledProductImportService.MAX_PRODUCTS_PER_REQUEST) {
            return HttpResponse.badRequest(
                mapOf("error" to "At most ${InstalledProductImportService.MAX_PRODUCTS_PER_REQUEST} products can be imported per request")
            )
        }

        log.info(
            "Installed products import request: products={}, dryRun={}, importRunId={}, user={}",
            request.products.size,
            request.dryRun,
            request.importRunId,
            authentication.name
        )
        return try {
            HttpResponse.ok(installedProductImportService.importProducts(request.products, request.dryRun, request.importRunId))
        } catch (e: IllegalArgumentException) {
            HttpResponse.badRequest(mapOf("error" to (e.message ?: "Invalid request")))
        } catch (e: Exception) {
            log.error("Installed products import failed for user={}", authentication.name, e)
            HttpResponse.serverError(mapOf("error" to "An internal error occurred while importing installed products"))
        }
    }
}
