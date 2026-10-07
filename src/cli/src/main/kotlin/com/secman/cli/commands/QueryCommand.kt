package com.secman.cli.commands

import com.secman.cli.config.ConfigLoader
import com.secman.cli.export.ExportService
import com.secman.cli.service.CliHttpClient
import com.secman.cli.service.VulnerabilityStorageService
import com.secman.cli.service.buildCrowdStrikeServerBatches
import com.secman.crowdstrike.client.CrowdStrikeApiClient
import com.secman.crowdstrike.dto.FalconConfigDto
import com.secman.crowdstrike.exception.AuthenticationException
import com.secman.crowdstrike.exception.CrowdStrikeException
import com.secman.crowdstrike.exception.NotFoundException
import com.secman.crowdstrike.exception.RateLimitException
import io.micronaut.context.ApplicationContext
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Query command for CrowdStrike vulnerabilities
 *
 * Usage:
 *   secman query --hostname <hostname> [options]
 *
 * Functionality:
 * - Query CrowdStrike Falcon API for vulnerabilities
 * - Filter by severity and product
 * - Support pagination
 * - Export to JSON or CSV
 * - Optionally save to database via backend HTTP API (--save flag)
 */
class QueryCommand(
    private val appContext: ApplicationContext = ApplicationContext.run(),
    private val getenv: (String) -> String? = System::getenv
) {
    private val log = LoggerFactory.getLogger(QueryCommand::class.java)
    private val configLoader = ConfigLoader()
    private val exportService = ExportService()
    private val apiClient: CrowdStrikeApiClient = appContext.getBean(CrowdStrikeApiClient::class.java)
    private val storageService: VulnerabilityStorageService = appContext.getBean(VulnerabilityStorageService::class.java)
    private val cliHttpClient: CliHttpClient = appContext.getBean(CliHttpClient::class.java)

    var hostname: String = ""
    var outputPath: String? = null
    var outputFile: String? = null
    var format: String = "json"
    var severity: String? = null
    var product: String? = null
    var limit: Int = 100
    var clientId: String? = null
    var clientSecret: String? = null
    var verbose: Boolean = false
    var save: Boolean = false

    fun execute(): Int {
        return try {
            log.info("Querying vulnerabilities for hostname: {}", hostname)

            if (hostname.isBlank()) {
                System.err.println("Error: Hostname cannot be blank")
                return 1
            }

            if (save && product != null) {
                System.err.println("Error: a product-filtered lookup cannot replace a complete host snapshot; omit --product when saving")
                return 2
            }

            val config = if (clientId != null && clientSecret != null) {
                FalconConfigDto(clientId = clientId!!, clientSecret = clientSecret!!)
            } else {
                configLoader.loadConfig()
            }

            log.info("Configuration loaded successfully")

            log.info("Querying CrowdStrike API for hostname: {}", hostname)
            System.out.println("Querying vulnerabilities for: $hostname")

            val response = apiClient.queryAllVulnerabilities(hostname, config)

            val filteredByServerity = if (severity != null) {
                val severityLevels = severity!!.split(",").map { it.trim().lowercase() }
                if (verbose) {
                    System.out.println("Filtering by severity: ${severityLevels.joinToString(", ")}")
                }
                response.copy(
                    vulnerabilities = response.vulnerabilities.filter {
                        severityLevels.contains(it.severity.lowercase())
                    }
                )
            } else {
                response
            }

            val filteredByProduct = if (product != null) {
                filteredByServerity.copy(
                    vulnerabilities = filteredByServerity.vulnerabilities.filter {
                        it.affectedProduct?.contains(product!!, ignoreCase = true) == true
                    }
                )
            } else {
                filteredByServerity
            }

            val finalResponse = filteredByProduct

            val failedDevices = finalResponse.failedAids.size
            val allDevicesFailed = failedDevices > 0 && finalResponse.devices.all {
                it.crowdStrikeAid in finalResponse.failedAids
            }
            when {
                allDevicesFailed -> System.out.println("Vulnerability count unavailable: all $failedDevices device queries failed")
                failedDevices > 0 -> System.out.println("Partial vulnerabilities found: ${finalResponse.vulnerabilities.size} ($failedDevices device queries failed)")
                else -> System.out.println("Total vulnerabilities found: ${finalResponse.vulnerabilities.size}")
            }

            if (verbose && finalResponse.vulnerabilities.isNotEmpty()) {
                val severityCount = finalResponse.vulnerabilities
                    .groupBy { it.severity }
                    .mapValues { it.value.size }
                    .toList()
                    .sortedByDescending { it.second }

                System.out.println("\nSeverity breakdown:")
                severityCount.forEach { (sev, count) ->
                    System.out.println("  - $sev: $count")
                }
            }

            // Save to database via backend HTTP API if --save flag is specified
            if (finalResponse.failedAids.isNotEmpty()) {
                System.err.println("Incomplete lookup for ${finalResponse.failedAids.size} device(s)")
                if (save) return 2
            }

            if (save && (finalResponse.vulnerabilities.isNotEmpty() || finalResponse.devices.isNotEmpty())) {
                // Authenticate with backend before import
                val backendUrl = getenv("SECMAN_BACKEND_URL")
                    ?: getenv("SECMAN_HOST")
                    ?: "http://localhost:8080"
                val username = getenv("SECMAN_ADMIN_NAME")
                val password = getenv("SECMAN_ADMIN_PASS")

                if (username.isNullOrBlank() || password.isNullOrBlank()) {
                    System.err.println("Error: SECMAN_ADMIN_NAME and SECMAN_ADMIN_PASS environment variables are required for --save")
                    return 1
                }

                val authToken = cliHttpClient.authenticate(username, password, backendUrl)
                if (authToken == null) {
                    System.err.println("Error: Failed to connect to backend API at $backendUrl. See error details above.")
                    return 1
                }

                System.out.println("\nSaving to database via backend API...")

                val serverBatches = buildCrowdStrikeServerBatches(finalResponse.vulnerabilities, finalResponse.devices)

                val result = storageService.storeServerVulnerabilities(
                    serverBatches = serverBatches,
                    authToken = authToken,
                    runSeverities = severity?.split(",")?.map(String::trim)?.filter(String::isNotBlank)
                )

                System.out.println(if (result.errors.isEmpty()) "Save completed!" else "Save failed")
                System.out.println("  - Asset: ${if (result.serversCreated > 0) "CREATED" else "UPDATED"}")
                System.out.println("  - Vulnerabilities imported: ${result.vulnerabilitiesImported}")
                System.out.println("  - Vulnerabilities skipped: ${result.vulnerabilitiesSkipped}")

                if (result.errors.isNotEmpty()) {
                    System.err.println("  - Errors:")
                    result.errors.forEach { error ->
                        System.err.println("    - $error")
                    }
                    return 1
                }
            } else if (save && finalResponse.vulnerabilities.isEmpty()) {
                System.out.println("\nNo vulnerabilities to save")
            }

            // Export results if output file is specified
            if (outputFile != null && finalResponse.vulnerabilities.isNotEmpty()) {
                val outFile = File(outputFile!!)
                val exported = when (format.lowercase()) {
                    "csv" -> exportService.exportToCsv(finalResponse, outFile)
                    else -> exportService.exportToJson(finalResponse, outFile)
                }

                if (exported) {
                    System.out.println("Results exported to ${format.uppercase()}: ${outFile.absolutePath}")
                } else {
                    System.out.println("Export cancelled by user")
                }
            }

            if (finalResponse.failedAids.isEmpty()) 0 else 2
        } catch (e: NotFoundException) {
            System.err.println()
            System.err.println("Error: Hostname '$hostname' not found in CrowdStrike")
            System.err.println("   The hostname may not exist or may not be monitored by CrowdStrike Falcon.")
            System.err.println("   Please verify the hostname is correct and the device is enrolled.")
            1
        } catch (e: AuthenticationException) {
            System.err.println()
            System.err.println("Error: CrowdStrike authentication failed")
            System.err.println("   Please check your CrowdStrike API credentials.")
            System.err.println("   Run 'secman config --show' to verify your configuration.")
            1
        } catch (e: RateLimitException) {
            System.err.println()
            System.err.println("Error: CrowdStrike API rate limit exceeded")
            System.err.println("   Please wait a few minutes before trying again.")
            1
        } catch (e: CrowdStrikeException) {
            System.err.println()
            System.err.println("Error: CrowdStrike API error")
            System.err.println("   ${e.message}")
            if (verbose) {
                e.printStackTrace()
            }
            1
        } catch (e: IllegalStateException) {
            System.err.println("Error: ${e.message}")
            1
        } catch (e: IllegalArgumentException) {
            System.err.println("Error: ${e.message}")
            1
        } catch (e: Exception) {
            System.err.println("Error: ${e.message}")
            if (verbose) {
                e.printStackTrace()
            }
            1
        }
    }

    private fun parseDaysOpenToInt(daysOpen: String?): Int {
        if (daysOpen.isNullOrBlank()) return 0
        return daysOpen.split(" ").firstOrNull()?.toIntOrNull() ?: 0
    }
}
