package com.secman.cli.commands

import com.secman.cli.config.ConfigLoader
import com.secman.cli.export.ExportService
import com.secman.cli.service.VulnerabilityStorageService
import com.secman.crowdstrike.auth.CrowdStrikeAuthService
import com.secman.crowdstrike.client.CrowdStrikeApiClient
import com.secman.crowdstrike.client.CrowdStrikeApiClientImpl
import com.secman.crowdstrike.dto.FalconConfigDto
import com.secman.crowdstrike.exception.AuthenticationException
import com.secman.crowdstrike.exception.CrowdStrikeException
import com.secman.crowdstrike.exception.NotFoundException
import com.secman.crowdstrike.exception.RateLimitException
import io.micronaut.context.ApplicationContext
import io.micronaut.http.client.HttpClient
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
 * - Optionally save to database (--save flag)
 *
 * Related to: Feature 023-create-in-the, 032-servers-query-import
 * Task: T049, T057-T060
 */
class QueryCommand {
    private val log = LoggerFactory.getLogger(QueryCommand::class.java)
    private val configLoader = ConfigLoader()
    private val exportService = ExportService()
    private val appContext = ApplicationContext.run()
    private val apiClient: CrowdStrikeApiClient = appContext.getBean(CrowdStrikeApiClient::class.java)
    private val storageService: VulnerabilityStorageService = appContext.getBean(VulnerabilityStorageService::class.java)

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
    var save: Boolean = false  // New: save to database flag
    var backendUrl: String = "http://localhost:8080"  // New: backend API URL
    var username: String? = null  // Backend username for authentication
    var password: String? = null  // Backend password for authentication

    fun execute(): Int {
        return try {
            log.info("Querying vulnerabilities for hostname: {}", hostname)

            // Validate input
            if (hostname.isBlank()) {
                System.err.println("Error: Hostname cannot be blank")
                return 1
            }

            // Load or override configuration
            val config = if (clientId != null && clientSecret != null) {
                FalconConfigDto(
                    clientId = clientId!!,
                    clientSecret = clientSecret!!
                )
            } else {
                configLoader.loadConfig()
            }

            log.info("Configuration loaded successfully")

            // Query CrowdStrike API
            log.info("Querying CrowdStrike API for hostname: {}", hostname)
            System.out.println("Querying vulnerabilities for: $hostname")

            val response = apiClient.queryAllVulnerabilities(hostname, config, limit)

            // Filter by severity if specified (supports comma-separated values)
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

            // Filter by product if specified
            val filteredByProduct = if (product != null) {
                filteredByServerity.copy(
                    vulnerabilities = filteredByServerity.vulnerabilities.filter {
                        it.affectedProduct?.contains(product!!, ignoreCase = true) == true
                    }
                )
            } else {
                filteredByServerity
            }

            // Apply final filters
            val finalResponse = filteredByProduct

            System.out.println("Total vulnerabilities found: ${finalResponse.vulnerabilities.size}")

            // Show severity breakdown if verbose
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

            // Save to database if --save flag is specified
            if (save && finalResponse.vulnerabilities.isNotEmpty()) {
                System.out.println("\nSaving to database: $backendUrl")

                // Authenticate with backend if credentials provided
                var authToken: String? = null
                if (username != null && password != null) {
                    System.out.println("Authenticating as: $username")
                    authToken = storageService.authenticate(username!!, password!!, backendUrl)

                    if (authToken == null) {
                        System.err.println("❌ Authentication failed. Please check your credentials.")
                        return 1
                    }
                    System.out.println("✅ Authentication successful")
                } else {
                    System.out.println("⚠️  No credentials provided. Attempting unauthenticated save...")
                    System.out.println("   Use --username and --password to authenticate")
                }

                val saveResult = storageService.storeVulnerabilities(
                    hostname = hostname,
                    vulnerabilities = finalResponse.vulnerabilities,
                    backendUrl = backendUrl,
                    authToken = authToken
                )

                if (saveResult.errors.isNotEmpty()) {
                    System.err.println("❌ Save failed:")
                    saveResult.errors.forEach { error ->
                        System.err.println("   - $error")
                    }
                    return 1
                }

                System.out.println("✅ Save successful!")
                System.out.println("  - Asset: ${if (saveResult.assetsCreated > 0) "CREATED" else "UPDATED"}")
                System.out.println("  - Vulnerabilities saved: ${saveResult.stored}")
                System.out.println("  - Vulnerabilities skipped: ${saveResult.skipped}")
                System.out.println("  - Message: ${saveResult.message}")
            } else if (save && finalResponse.vulnerabilities.isEmpty()) {
                System.out.println("\n⚠️  No vulnerabilities to save")
            }

            // Export results if output file is specified
            if (outputFile != null && finalResponse.vulnerabilities.isNotEmpty()) {
                val outFile = File(outputFile!!)
                val exported = when (format.lowercase()) {
                    "csv" -> {
                        exportService.exportToCsv(finalResponse, outFile)
                    }
                    else -> {
                        exportService.exportToJson(finalResponse, outFile)
                    }
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
}
