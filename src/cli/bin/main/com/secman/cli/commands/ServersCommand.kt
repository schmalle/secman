package com.secman.cli.commands

import com.secman.cli.config.ConfigLoader
import com.secman.cli.service.ServerVulnerabilityBatch
import com.secman.cli.service.VulnerabilityStorageService
import com.secman.crowdstrike.client.CrowdStrikeApiClient
import com.secman.crowdstrike.dto.FalconConfigDto
import io.micronaut.context.ApplicationContext
import org.slf4j.LoggerFactory

/**
 * Servers command for querying CrowdStrike server vulnerabilities with auto-import
 *
 * Usage:
 *   secman query servers [options]
 *
 * Functionality:
 * - Query CrowdStrike Falcon API for server vulnerabilities (device type: SERVER)
 * - Filter by severity (HIGH, CRITICAL) and days open (>=30 by default)
 * - Optionally filter by specific hostnames
 * - Automatically import discovered servers as Asset records with vulnerabilities
 * - Support dry-run mode (query without importing)
 * - Support verbose logging
 *
 * Feature: 032-servers-query-import
 * Tasks: T009, T012, T013, T014, T015, T016
 * Spec reference: FR-001, FR-002, FR-003, FR-004, FR-005, FR-007, FR-009
 */
class ServersCommand {
    private val log = LoggerFactory.getLogger(ServersCommand::class.java)
    private val configLoader = ConfigLoader()
    private val appContext = ApplicationContext.run()
    private val apiClient: CrowdStrikeApiClient = appContext.getBean(CrowdStrikeApiClient::class.java)
    private val storageService: VulnerabilityStorageService = appContext.getBean(VulnerabilityStorageService::class.java)

    // Command-line options
    var hostnames: List<String>? = null  // Optional hostname filter (FR-003)
    var deviceType: String = "SERVER"    // Device type filter (FR-002)
    var severity: String = "HIGH,CRITICAL"  // Severity filter (FR-004)
    var minDaysOpen: Int = 30            // Minimum days open filter (FR-004)
    var dryRun: Boolean = false          // Dry-run mode (FR-005)
    var save: Boolean = false            // Save to database (requires username/password)
    var verbose: Boolean = false         // Verbose logging (FR-009)
    var backendUrl: String = "http://localhost:8080"  // Backend API URL
    var username: String? = null         // Backend username for authentication
    var password: String? = null         // Backend password for authentication
    var clientId: String? = null         // CrowdStrike client ID (optional override)
    var clientSecret: String? = null     // CrowdStrike client secret (optional override)
    var limit: Int = 100                 // Page size for pagination

    /**
     * Execute the servers query command
     *
     * @return Exit code (0 = success, 1 = error)
     */
    fun execute(): Int {
        return try {
            if (verbose) {
                log.info("Starting servers query with filters: deviceType={}, severity={}, minDaysOpen={}, hostnames={}",
                    deviceType, severity, minDaysOpen, hostnames?.joinToString(",") ?: "ALL")
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

            if (verbose) {
                log.info("Configuration loaded successfully")
            }

            // Query CrowdStrike API with filters
            System.out.println("Querying CrowdStrike for servers...")
            if (verbose) {
                System.out.println("Filters: device type=$deviceType, severity=$severity, min days open=$minDaysOpen")
                if (hostnames != null) {
                    System.out.println("Hostnames: ${hostnames!!.joinToString(", ")}")
                }
            }

            val response = apiClient.queryServersWithFilters(
                hostnames = hostnames,
                deviceType = deviceType,
                severity = severity,
                minDaysOpen = minDaysOpen,
                config = config,
                limit = limit
            )

            System.out.println("Found ${response.vulnerabilities.size} vulnerabilities across servers")

            if (response.vulnerabilities.isEmpty()) {
                System.out.println("No vulnerabilities found matching criteria")
                return 0
            }

            // Group vulnerabilities by hostname for display
            val vulnerabilitiesByHostname = response.vulnerabilities.groupBy { it.hostname }
            System.out.println("Servers with vulnerabilities: ${vulnerabilitiesByHostname.size}")

            if (verbose) {
                vulnerabilitiesByHostname.forEach { (hostname, vulns) ->
                    System.out.println("  - $hostname: ${vulns.size} vulnerabilities")
                }
            }

            // Dry-run mode: skip backend import
            if (dryRun) {
                System.out.println("\n[DRY-RUN MODE] Would import ${vulnerabilitiesByHostname.size} servers with ${response.vulnerabilities.size} vulnerabilities")
                return 0
            }

            // Skip import if --save not specified
            if (!save) {
                System.out.println("\nQuery completed successfully. Use --save --username <user> --password <pass> to import to database.")
                return 0
            }

            // Validate authentication credentials
            if (username == null || password == null) {
                System.err.println("Error: --username and --password are required when using --save")
                return 1
            }

            // Import to backend via storage service (T011)
            System.out.println("\nImporting to backend: $backendUrl")

            // Authenticate with backend
            if (verbose) {
                System.out.println("Authenticating with backend...")
            }

            val authToken = storageService.authenticate(username!!, password!!, backendUrl)
            if (authToken == null) {
                System.err.println("Error: Authentication failed. Please check your username and password.")
                return 1
            }

            if (verbose) {
                System.out.println("Authentication successful")
            }

            // Prepare server batches with metadata
            val serverBatches = vulnerabilitiesByHostname.mapValues { (hostname, vulns) ->
                // Extract metadata from first vulnerability
                val firstVuln = vulns.firstOrNull()
                ServerVulnerabilityBatch(
                    hostname = hostname,
                    vulnerabilities = vulns,
                    groups = null,  // TODO: Not available from current CrowdStrike API response
                    cloudAccountId = null,  // TODO: Not available from current CrowdStrike API response
                    cloudInstanceId = null,  // TODO: Not available from current CrowdStrike API response
                    adDomain = null,  // TODO: Not available from current CrowdStrike API response
                    osVersion = null,  // TODO: Not available from current CrowdStrike API response
                    ip = firstVuln?.ip  // IP is available from CrowdStrikeVulnerabilityDto
                )
            }

            val result = storageService.storeServerVulnerabilities(serverBatches, backendUrl, authToken)

            // Display import statistics
            System.out.println("\n--- Import Statistics ---")
            System.out.println("Servers processed: ${result.serversProcessed}")
            System.out.println("  - New servers created: ${result.serversCreated}")
            System.out.println("  - Existing servers updated: ${result.serversUpdated}")
            System.out.println("Vulnerabilities imported: ${result.vulnerabilitiesImported}")
            System.out.println("Vulnerabilities skipped: ${result.vulnerabilitiesSkipped}")

            if (result.errors.isNotEmpty()) {
                System.err.println("\n--- Errors (${result.errors.size}) ---")
                result.errors.take(20).forEach { error ->
                    System.err.println("  - $error")
                }
                if (result.errors.size > 20) {
                    System.err.println("  ... and ${result.errors.size - 20} more errors")
                }
            }

            if (result.errors.isNotEmpty()) 1 else 0
        } catch (e: UnsupportedOperationException) {
            System.err.println("Error: ${e.message}")
            if (verbose) {
                e.printStackTrace()
            }
            1
        } catch (e: IllegalStateException) {
            System.err.println("Error: ${e.message}")
            if (verbose) {
                e.printStackTrace()
            }
            1
        } catch (e: IllegalArgumentException) {
            System.err.println("Error: ${e.message}")
            if (verbose) {
                e.printStackTrace()
            }
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
