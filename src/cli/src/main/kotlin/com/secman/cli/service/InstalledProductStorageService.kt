package com.secman.cli.service

import com.secman.crowdstrike.dto.InstalledProductDto
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpResponse
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import jakarta.inject.Singleton

@Singleton
class InstalledProductStorageService(
    @Client("\${secman.backend.base-url:http://localhost:8080}")
    private val httpClient: HttpClient
) {
    fun importInstalledProducts(
        products: List<InstalledProductDto>,
        dryRun: Boolean,
        authToken: String?,
        importRunId: String? = null
    ): InstalledProductStorageResult {
        val body = mutableMapOf<String, Any>("products" to products, "dryRun" to dryRun)
        if (importRunId != null) {
            body["importRunId"] = importRunId
        }
        val request = HttpRequest.POST("/api/installed-products/import", body)
            .contentType(io.micronaut.http.MediaType.APPLICATION_JSON)
        if (authToken != null) {
            request.header("Authorization", "Bearer $authToken")
        }
        val response: HttpResponse<Map<*, *>> = httpClient.toBlocking().exchange(request, Map::class.java)
        val responseBody = response.body()
        @Suppress("UNCHECKED_CAST")
        return InstalledProductStorageResult(
            productsProcessed = (responseBody?.get("productsProcessed") as? Number)?.toInt() ?: products.size,
            productsImported = (responseBody?.get("productsImported") as? Number)?.toInt() ?: 0,
            productsUpdated = (responseBody?.get("productsUpdated") as? Number)?.toInt() ?: 0,
            productsSkipped = (responseBody?.get("productsSkipped") as? Number)?.toInt() ?: 0,
            productsDeleted = (responseBody?.get("productsDeleted") as? Number)?.toInt() ?: 0,
            unknownSystems = (responseBody?.get("unknownSystems") as? Number)?.toInt() ?: 0,
            dryRun = responseBody?.get("dryRun") as? Boolean ?: dryRun,
            errors = responseBody?.get("errors") as? List<String> ?: emptyList(),
            unknownSystemSamples = responseBody?.get("unknownSystemSamples") as? List<String> ?: emptyList()
        )
    }
}

data class InstalledProductStorageResult(
    val productsProcessed: Int,
    val productsImported: Int,
    val productsUpdated: Int,
    val productsSkipped: Int,
    val productsDeleted: Int,
    val unknownSystems: Int,
    val dryRun: Boolean,
    val errors: List<String>,
    val unknownSystemSamples: List<String> = emptyList()
)
