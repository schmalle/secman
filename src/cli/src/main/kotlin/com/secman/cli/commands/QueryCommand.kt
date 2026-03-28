package com.secman.cli.commands

import com.secman.cli.config.ConfigLoader
import com.secman.cli.export.ExportService
import com.secman.cli.service.CliHttpClient
import com.secman.cli.service.VulnerabilityStorageService
import com.secman.cli.service.ServerVulnerabilityBatch
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
class QueryCommand {
    private val log = LoggerFactory.getLogger(QueryCommand::class.java)
    private val configLoader = ConfigLoader()
    private val exportService = ExportService()
    private val appContext = ApplicationContext.run()
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

            val config = if (clientId != null && clientSecret != null) {
                FalconConfigDto(clientId = clientId!!, clientSecret = clientSecret!!)
            } else {
                configLoader.loadConfig()
            }

            log.info("Configuration loaded successfully")

            log.info("Querying CrowdStrike API for hostname: {}", hostname)
            System.out.println("Querying vulnerabilities for: $hostname")

            val response = apiClient.queryAllVulnerabilities(hostname, config, limit)

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

            System.out.println("Total vulnerabilities found: ${finalResponse.vulnerabilities.size}")

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
            if (save && finalResponse.vulnerabilities.isNotEmpty()) {
                // Authenticate with backend before import
                val backendUrl = System.getenv("SECMAN_BACKEND_URL")
                    ?: System.getenv("SECMAN_HOST")
                    ?: "http://localhost:8080"
                val username = System.getenv("SECMAN_ADMIN_NAME")
                val password = System.getenv("SECMAN_ADMIN_PASS")

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

                val firstVuln = finalResponse.vulnerabilities.firstOrNull()
                val latestCloudInstanceId = finalResponse.vulnerabilities
                    .filter { !it.cloudInstanceId.isNullOrBlank() }
                    .maxByOrNull { it.detectedAt }
                    ?.cloudInstanceId

                val serverBatch = ServerVulnerabilityBatch(
                    hostname = hostname,
                    vulnerabilities = finalResponse.vulnerabilities,
                    groups = null,
                    cloudAccountId = firstVuln?.cloudAccountId,
                    cloudInstanceId = latestCloudInstanceId ?: firstVuln?.cloudInstanceId,
                    adDomain = firstVuln?.adDomain,
                    osVersion = null,
                    ip = firstVuln?.ip
                )

                val result = storageService.storeServerVulnerabilities(
                    serverBatches = mapOf(hostname to serverBatch),
                    authToken = authToken
                )

                System.out.println("Save completed!")
                System.out.println("  - Asset: ${if (result.serversCreated > 0) "CREATED" else "UPDATED"}")
                System.out.println("  - Vulnerabilities imported: ${result.vulnerabilitiesImported}")
                System.out.println("  - Vulnerabilities skipped: ${result.vulnerabilitiesSkipped}")

                if (result.errors.isNotEmpty()) {
                    System.err.println("  - Errors:")
                    result.errors.forEach { error ->
                        System.err.println("    - $error")
                    }
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

            0
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
