package com.secman.controller

import com.secman.dto.InstalledProductImportRequest
import com.secman.dto.InstalledProductListResponse
import com.secman.dto.InstalledProductResponse
import com.secman.repository.InstalledProductRepository
import com.secman.service.InstalledProductImportService
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
    private val installedProductRepository: InstalledProductRepository,
    private val installedProductImportService: InstalledProductImportService
) {
    private val log = LoggerFactory.getLogger(InstalledProductController::class.java)

    @Get
    open fun list(
        @Nullable @QueryValue search: String?,
        @Nullable @QueryValue limit: Int?
    ): HttpResponse<InstalledProductListResponse> {
        val effectiveLimit = (limit ?: 500).coerceIn(1, 2000)
        val normalizedSearch = search?.trim().orEmpty()
        val products = installedProductRepository.search(normalizedSearch).take(effectiveLimit)
        return HttpResponse.ok(
            InstalledProductListResponse(
                products = products.map { product ->
                    InstalledProductResponse(
                        id = requireNotNull(product.id),
                        assetId = requireNotNull(product.asset.id),
                        hostname = product.asset.name,
                        name = product.name,
                        vendor = product.vendor,
                        version = product.version,
                        category = product.category,
                        installationPath = product.installationPath,
                        installedAt = product.installedAt,
                        lastUsedAt = product.lastUsedAt,
                        lastUpdatedAt = product.lastUpdatedAt,
                        importedAt = product.importedAt
                    )
                },
                totalProducts = products.size,
                totalSystems = installedProductRepository.countDistinctAssets(normalizedSearch)
            )
        )
    }

    @Post("/import")
    @Secured("ADMIN", "VULN")
    open fun importProducts(
        @Body @Valid request: InstalledProductImportRequest,
        authentication: Authentication
    ): HttpResponse<*> {
        log.info(
            "Installed products import request: products={}, dryRun={}, user={}",
            request.products.size,
            request.dryRun,
            authentication.name
        )
        return try {
            HttpResponse.ok(installedProductImportService.importProducts(request.products, request.dryRun))
        } catch (e: IllegalArgumentException) {
            HttpResponse.badRequest(mapOf("error" to (e.message ?: "Invalid request")))
        } catch (e: Exception) {
            log.error("Installed products import failed for user={}", authentication.name, e)
            HttpResponse.serverError(mapOf("error" to "An internal error occurred while importing installed products"))
        }
    }
}
