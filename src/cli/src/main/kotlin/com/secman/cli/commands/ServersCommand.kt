package com.secman.cli.commands

import com.secman.cli.config.ConfigLoader
import com.secman.crowdstrike.client.CrowdStrikeApiClient
import com.secman.crowdstrike.dto.DeviceType
import com.secman.crowdstrike.dto.FalconConfigDto
import com.secman.crowdstrike.exception.AuthenticationException
import com.secman.crowdstrike.exception.CrowdStrikeException
import com.secman.crowdstrike.exception.NotFoundException
import com.secman.crowdstrike.exception.RateLimitException
import com.secman.dto.CrowdStrikeVulnerabilityBatchDto
import com.secman.dto.VulnerabilityDto
import com.secman.service.CrowdStrikeVulnerabilityImportService
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
 * Feature: 032-servers-query-import, 073-cli-direct-db
 */
class ServersCommand {
    private val log = LoggerFactory.getLogger(ServersCommand::class.java)
    private val configLoader = ConfigLoader()
    private val appContext = ApplicationContext.run()
    private val apiClient: CrowdStrikeApiClient = appContext.getBean(CrowdStrikeApiClient::class.java)
    private val importService: CrowdStrikeVulnerabilityImportService = appContext.getBean(CrowdStrikeVulnerabilityImportService::class.java)

    // Command-line options
    var hostnames: List<String>? = null  // Optional hostname filter (FR-003)
    var deviceType: String = "SERVER"    // Device type filter (FR-002)
    var severity: String = "HIGH,CRITICAL"  // Severity filter (FR-004)
    var minDaysOpen: Int = 30            // Minimum days open filter (FR-004)
    var dryRun: Boolean = false          // Dry-run mode (FR-005)
    var save: Boolean = false            // Save to database (direct access)
    var verbose: Boolean = false         // Verbose logging (FR-009)
    var clientId: String? = null         // CrowdStrike client ID (optional override)
    var clientSecret: String? = null     // CrowdStrike client secret (optional override)
    var limit: Int = 800                 // Page size for pagination
    var lastSeenDays: Int = 0            // Only include devices seen within N days (0 = all devices)

    /**
     * Execute the servers query command
     *
     * @return Exit code (0 = success, 1 = error)
     */
    fun execute(): Int {
        return try {
            // Validate and parse device type (case-insensitive)
            val parsedDeviceType = try {
                DeviceType.fromString(deviceType)
            } catch (e: IllegalArgumentException) {
                System.err.println("Error: ${e.message}")
                return 1
            }

            // Always show which device type is being queried
            System.out.println("Device type: ${parsedDeviceType.name}")

            if (verbose) {
                log.info("Starting query with filters: deviceType={}, severity={}, minDaysOpen={}, lastSeenDays={}, hostnames={}",
                    parsedDeviceType.name, severity, minDaysOpen, lastSeenDays, hostnames?.joinToString(",") ?: "ALL")
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
            System.out.println("Querying CrowdStrike for ${parsedDeviceType.displayName()}...")
            if (verbose) {
                System.out.println("Filters: device type=${parsedDeviceType.name}, severity=$severity, min days open=$minDaysOpen, last seen days=${if (lastSeenDays > 0) lastSeenDays else "all"}")
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
                limit = limit,
                lastSeenDays = lastSeenDays
            )

            System.out.println("Found ${response.vulnerabilities.size} vulnerabilities across ${parsedDeviceType.displayName()}")

            if (response.vulnerabilities.isEmpty()) {
                System.out.println("No vulnerabilities found matching criteria")
                return 0
            }

            // Group vulnerabilities by hostname for display
            val vulnerabilitiesByHostname = response.vulnerabilities.groupBy { it.hostname }
            System.out.println("${parsedDeviceType.displayName().replaceFirstChar { it.uppercase() }} with vulnerabilities: ${vulnerabilitiesByHostname.size}")

            if (verbose) {
                vulnerabilitiesByHostname.forEach { (hostname, vulns) ->
                    System.out.println("  - $hostname: ${vulns.size} vulnerabilities")
                }
            }

            // Dry-run mode: skip import
            if (dryRun) {
                System.out.println("\n[DRY-RUN MODE] Would import ${vulnerabilitiesByHostname.size} ${parsedDeviceType.displayName()} with ${response.vulnerabilities.size} vulnerabilities")
                return 0
            }

            // Skip import if --save not specified
            if (!save) {
                System.out.println("\nQuery completed successfully. Use --save to import directly to database.")
                return 0
            }

            // Import directly to database via import service
            System.out.println("\nImporting to database...")

            // Map CrowdStrike DTOs to backend batch DTOs
            val batches = vulnerabilitiesByHostname.map { (hostname, vulns) ->
                val firstVuln = vulns.firstOrNull()
                CrowdStrikeVulnerabilityBatchDto(
                    hostname = hostname,
                    groups = null,
                    cloudAccountId = firstVuln?.cloudAccountId,
                    cloudInstanceId = firstVuln?.cloudInstanceId,
                    adDomain = firstVuln?.adDomain,
                    osVersion = null,
                    ip = firstVuln?.ip,
                    vulnerabilities = vulns.map { vuln ->
                        VulnerabilityDto(
                            cveId = vuln.cveId ?: "",
                            severity = vuln.severity,
                            affectedProduct = vuln.affectedProduct,
                            daysOpen = parseDaysOpenToInt(vuln.daysOpen),
                            patchPublicationDate = vuln.patchPublicationDate
                        )
                    }
                )
            }

            val result = importService.importServerVulnerabilities(batches, "CLI")

            // Display import statistics
            val deviceLabel = parsedDeviceType.displayName().replaceFirstChar { it.uppercase() }
            System.out.println("\n--- Import Statistics ---")
            System.out.println("$deviceLabel processed: ${result.serversProcessed}")
            System.out.println("  - New $deviceLabel created: ${result.serversCreated}")
            System.out.println("  - Existing $deviceLabel updated: ${result.serversUpdated}")
            System.out.println("Vulnerabilities imported: ${result.vulnerabilitiesImported}")
            System.out.println("  - With patch publication date: ${result.vulnerabilitiesWithPatchDate}")
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
        } catch (e: NotFoundException) {
            System.err.println()
            val hostnameInfo = if (hostnames != null && hostnames!!.size == 1) {
                "Hostname '${hostnames!!.first()}' not found in CrowdStrike"
            } else {
                "One or more hostnames not found in CrowdStrike"
            }
            System.err.println("Error: $hostnameInfo")
            System.err.println("   The hostname(s) may not exist or may not be monitored by CrowdStrike Falcon.")
            System.err.println("   Please verify the hostnames are correct and the devices are enrolled.")
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
            System.err.println("Error: [${e.javaClass.simpleName}] ${e.message}")
            if (e.cause != null) {
                System.err.println("  Caused by: [${e.cause!!.javaClass.simpleName}] ${e.cause!!.message}")
            }
            if (verbose) {
                e.printStackTrace()
            } else {
                System.err.println("  (Run with --verbose for full stack trace)")
            }
            1
        }
    }

    /**
     * Parse daysOpen string to integer value
     *
     * Handles formats: "526 days" -> 526, "1 day" -> 1, null -> 0
     */
    private fun parseDaysOpenToInt(daysOpen: String?): Int {
        if (daysOpen.isNullOrBlank()) return 0
        return daysOpen.split(" ").firstOrNull()?.toIntOrNull() ?: 0
    }
}
