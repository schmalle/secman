package com.secman.cli.commands

import com.secman.cli.config.ConfigLoader
import com.secman.cli.service.CliHttpClient
import com.secman.cli.service.InstalledProductStorageService
import com.secman.crowdstrike.client.CrowdStrikeApiClient
import com.secman.crowdstrike.dto.DeviceType
import com.secman.crowdstrike.dto.FalconConfigDto
import io.micronaut.context.ApplicationContext
import org.slf4j.LoggerFactory

class InstalledProductsCommand {
    private val log = LoggerFactory.getLogger(InstalledProductsCommand::class.java)
    private val configLoader = ConfigLoader()

    var deviceType: String = "SERVER"
    var dryRun: Boolean = false
    var verbose: Boolean = false
    var clientId: String? = null
    var clientSecret: String? = null
    var limit: Int = 1000
    var backendUrl: String? = null

    private val appContext by lazy {
        val resolvedUrl = resolveBackendUrl()
        ApplicationContext.builder()
            .properties(mapOf("secman.backend.base-url" to resolvedUrl))
            .start()
    }
    private val apiClient: CrowdStrikeApiClient by lazy { appContext.getBean(CrowdStrikeApiClient::class.java) }
    private val storageService: InstalledProductStorageService by lazy { appContext.getBean(InstalledProductStorageService::class.java) }
    private val cliHttpClient: CliHttpClient by lazy { appContext.getBean(CliHttpClient::class.java) }

    fun execute(): Int {
        return try {
            val parsedDeviceType = DeviceType.fromString(deviceType)
            val config = if (clientId != null && clientSecret != null) {
                FalconConfigDto(clientId = clientId!!, clientSecret = clientSecret!!)
            } else {
                configLoader.loadConfig()
            }
            val resolvedBackendUrl = resolveBackendUrl()
            val authToken = if (!dryRun) authenticate(resolvedBackendUrl) else null

            System.out.println("Importing installed products from CrowdStrike")
            System.out.println("Device type: ${parsedDeviceType.name}")
            System.out.println("Mode: ${if (dryRun) "dry run" else "import"}")

            var totalQueried = 0
            var totalImported = 0
            var totalUpdated = 0
            var totalSkipped = 0
            var totalUnknownSystems = 0
            var batches = 0

            val total = apiClient.queryInstalledProductsStreaming(
                deviceType = parsedDeviceType.name,
                config = config,
                limit = limit.coerceIn(1, 1000)
            ) { products ->
                batches++
                totalQueried += products.size
                if (dryRun) {
                    val hostCount = products.map { it.hostname.lowercase() }.distinct().size
                    System.out.println("Dry-run batch $batches: ${products.size} product rows across $hostCount CrowdStrike hosts")
                } else {
                    val result = storageService.importInstalledProducts(products, dryRun = false, authToken = authToken)
                    totalImported += result.productsImported
                    totalUpdated += result.productsUpdated
                    totalSkipped += result.productsSkipped
                    totalUnknownSystems += result.unknownSystems
                    if (verbose && result.errors.isNotEmpty()) {
                        result.errors.forEach { System.err.println("  - $it") }
                    }
                    System.out.println("Batch $batches: imported=${result.productsImported}, updated=${result.productsUpdated}, skipped=${result.productsSkipped}, unknown systems=${result.unknownSystems}")
                }
            }

            System.out.println("\n--- Installed Products Summary ---")
            System.out.println("CrowdStrike rows processed: $total")
            System.out.println("Batches: $batches")
            if (!dryRun) {
                System.out.println("Products imported: $totalImported")
                System.out.println("Products updated: $totalUpdated")
                System.out.println("Products skipped: $totalSkipped")
                System.out.println("Unknown Secman systems: $totalUnknownSystems")
            } else {
                System.out.println("Dry run only; no backend data was changed.")
            }
            0
        } catch (e: IllegalArgumentException) {
            System.err.println("Error: ${e.message}")
            1
        } catch (e: Exception) {
            log.error("Installed products import failed", e)
            System.err.println("Error: ${e.message}")
            1
        }
    }

    private fun authenticate(resolvedBackendUrl: String): String? {
        val username = System.getenv("SECMAN_ADMIN_NAME")
        val password = System.getenv("SECMAN_ADMIN_PASS")
        if (username.isNullOrBlank() || password.isNullOrBlank()) {
            throw IllegalArgumentException("SECMAN_ADMIN_NAME and SECMAN_ADMIN_PASS environment variables are required")
        }
        return cliHttpClient.authenticate(username, password, resolvedBackendUrl)
            ?: throw IllegalStateException("Failed to authenticate with backend API at $resolvedBackendUrl")
    }

    private fun resolveBackendUrl(): String = backendUrl
        ?: System.getenv("SECMAN_BACKEND_URL")
        ?: System.getenv("SECMAN_HOST")?.let { host ->
            if (host.startsWith("http://") || host.startsWith("https://")) host else "https://$host"
        }
        ?: "http://localhost:8080"
}
