package com.secman.cli.commands

import com.secman.cli.config.ConfigLoader
import com.secman.crowdstrike.client.CrowdStrikeApiClient
import com.secman.crowdstrike.client.StreamingSummary
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
    companion object {
        private const val MAX_RETAINED_ERRORS = 100
    }

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
    var overdueThreshold: Int = 30       // Threshold in days for overdue vulnerability report

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
            logMemoryUsage("after config load")

            // Query CrowdStrike API with filters
            System.out.println("Querying CrowdStrike for ${parsedDeviceType.displayName()}...")
            if (verbose) {
                System.out.println("Filters: device type=${parsedDeviceType.name}, severity=$severity, min days open=$minDaysOpen, last seen days=${if (lastSeenDays > 0) lastSeenDays else "all"}")
                if (hostnames != null) {
                    System.out.println("Hostnames: ${hostnames!!.joinToString(", ")}")
                }
            }

            // When --save is specified and no specific hostnames, use streaming mode
            // to avoid holding all vulnerabilities in memory at once
            if (save && hostnames.isNullOrEmpty()) {
                System.out.println("Querying and importing in streaming mode...")

                var totalServersProcessed = 0
                var totalServersCreated = 0
                var totalServersUpdated = 0
                var totalVulnsImported = 0
                var totalVulnsWithPatchDate = 0
                var totalVulnsSkipped = 0
                val allErrors = mutableListOf<String>()
                var totalErrorCount = 0
                var streamBatchNum = 0
                var totalSystemsWithOverdueVulns = 0

                val totalVulns = apiClient.queryServersWithFiltersStreaming(
                    deviceType = deviceType,
                    severity = severity,
                    minDaysOpen = minDaysOpen,
                    config = config,
                    limit = limit,
                    lastSeenDays = lastSeenDays,
                    deviceBatchSize = 200
                ) { batchVulns ->
                    streamBatchNum++
                    val byHostname = batchVulns.groupBy { it.hostname }
                    System.out.println("  Stream batch $streamBatchNum: ${batchVulns.size} vulns across ${byHostname.size} hosts")

                    val batches = byHostname.map { (hostname, vulns) ->
                        val firstVuln = vulns.firstOrNull()
                        // Feature 082: Use cloudInstanceId from the most recently detected vulnerability
                        // When EC2 instances are replaced, CrowdStrike may report both old and new devices
                        // under the same hostname. Using the latest detection ensures we pick up the new instance ID.
                        val latestCloudInstanceId = vulns
                            .filter { !it.cloudInstanceId.isNullOrBlank() }
                            .maxByOrNull { it.detectedAt }
                            ?.cloudInstanceId
                        CrowdStrikeVulnerabilityBatchDto(
                            hostname = hostname,
                            groups = null,
                            cloudAccountId = firstVuln?.cloudAccountId,
                            cloudInstanceId = latestCloudInstanceId ?: firstVuln?.cloudInstanceId,
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

                    // Count systems with overdue vulnerabilities (O(1) extra memory - just a counter)
                    totalSystemsWithOverdueVulns += batches.count { batch ->
                        batch.vulnerabilities.any { it.daysOpen > overdueThreshold }
                    }

                    val result = importService.importServerVulnerabilities(batches, "CLI", triggerRefresh = false)
                    totalServersProcessed += result.serversProcessed
                    totalServersCreated += result.serversCreated
                    totalServersUpdated += result.serversUpdated
                    totalVulnsImported += result.vulnerabilitiesImported
                    totalVulnsWithPatchDate += result.vulnerabilitiesWithPatchDate
                    totalVulnsSkipped += result.vulnerabilitiesSkipped
                    totalErrorCount += result.errors.size
                    if (allErrors.size < MAX_RETAINED_ERRORS) {
                        val remaining = MAX_RETAINED_ERRORS - allErrors.size
                        allErrors.addAll(result.errors.take(remaining))
                    }
                    logMemoryUsage("after stream batch $streamBatchNum")
                }

                if (totalVulns == 0) {
                    System.out.println("No vulnerabilities found matching criteria")
                    return 0
                }

                logMemoryUsage("streaming complete")
                val deviceLabel = parsedDeviceType.displayName().replaceFirstChar { it.uppercase() }
                System.out.println("\n--- Import Statistics ---")
                System.out.println("$deviceLabel processed: $totalServersProcessed")
                System.out.println("  - New $deviceLabel created: $totalServersCreated")
                System.out.println("  - Existing $deviceLabel updated: $totalServersUpdated")
                System.out.println("Vulnerabilities imported: $totalVulnsImported")
                System.out.println("  - With patch publication date: $totalVulnsWithPatchDate")
                System.out.println("Vulnerabilities skipped: $totalVulnsSkipped")

                // Vulnerability age summary
                if (totalServersProcessed > 0) {
                    val totalWithoutOverdue = totalServersProcessed - totalSystemsWithOverdueVulns
                    val percent = totalSystemsWithOverdueVulns * 100.0 / totalServersProcessed
                    System.out.println("\n--- Vulnerability Age Summary (>$overdueThreshold days) ---")
                    System.out.println("Servers with vulnerabilities older than $overdueThreshold days: $totalSystemsWithOverdueVulns of $totalServersProcessed (${String.format("%.1f", percent)}%)")
                    System.out.println("Servers with no vulnerabilities older than $overdueThreshold days: $totalWithoutOverdue of $totalServersProcessed")
                }

                if (totalErrorCount > 0) {
                    System.err.println("\n--- Errors ($totalErrorCount) ---")
                    allErrors.take(20).forEach { error ->
                        System.err.println("  - $error")
                    }
                    if (totalErrorCount > 20) {
                        System.err.println("  ... and ${totalErrorCount - 20} more errors")
                    }
                }

                return if (totalErrorCount > 0) 1 else 0
            }

            // When no specific hostnames: use streaming summary to avoid loading all vulns into memory.
            // This only retains hostname→count stats instead of 800K+ vulnerability DTOs (~320MB).
            if (hostnames.isNullOrEmpty()) {
                val summary = apiClient.queryServersWithFiltersSummary(
                    deviceType = deviceType,
                    severity = severity,
                    minDaysOpen = minDaysOpen,
                    config = config,
                    limit = limit,
                    lastSeenDays = lastSeenDays,
                    deviceBatchSize = 200,
                    overdueThreshold = overdueThreshold
                )

                System.out.println("Found ${summary.totalVulnerabilities} vulnerabilities across ${parsedDeviceType.displayName()}")

                if (summary.totalVulnerabilities == 0) {
                    System.out.println("No vulnerabilities found matching criteria")
                    return 0
                }

                System.out.println("${parsedDeviceType.displayName().replaceFirstChar { it.uppercase() }} with vulnerabilities: ${summary.hostCounts.size}")
                logMemoryUsage("after summary query")

                if (verbose) {
                    summary.hostCounts.entries.sortedByDescending { it.value }.forEach { (hostname, count) ->
                        System.out.println("  - $hostname: $count vulnerabilities")
                    }
                }

                // Vulnerability age summary
                if (summary.hostCounts.isNotEmpty()) {
                    val totalHosts = summary.hostCounts.size
                    val hostsWithOverdue = summary.hostsWithOverdueVulns
                    val hostsWithoutOverdue = totalHosts - hostsWithOverdue
                    val overduePercent = hostsWithOverdue * 100.0 / totalHosts
                    System.out.println("\n--- Vulnerability Age Summary (>$overdueThreshold days) ---")
                    System.out.println("Servers with vulnerabilities older than $overdueThreshold days: $hostsWithOverdue of $totalHosts (${String.format("%.1f", overduePercent)}%)")
                    System.out.println("Servers with no vulnerabilities older than $overdueThreshold days: $hostsWithoutOverdue of $totalHosts")
                }

                if (dryRun) {
                    System.out.println("\n[DRY-RUN MODE] Would import ${summary.hostCounts.size} ${parsedDeviceType.displayName()} with ${summary.totalVulnerabilities} vulnerabilities")
                }
                if (!save || dryRun) {
                    System.out.println("\nQuery completed successfully. Use --save to import directly to database.")
                }
                return 0
            }

            // Hostname-specific queries: load full DTOs (bounded by the number of specified hosts)
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

            // Vulnerability age summary
            val hostsWithOverdue = vulnerabilitiesByHostname.count { (_, vulns) ->
                vulns.any { parseDaysOpenToInt(it.daysOpen) > overdueThreshold }
            }
            val totalHosts = vulnerabilitiesByHostname.size
            val hostsWithoutOverdue = totalHosts - hostsWithOverdue
            val overduePercent = hostsWithOverdue * 100.0 / totalHosts
            System.out.println("\n--- Vulnerability Age Summary (>$overdueThreshold days) ---")
            System.out.println("Servers with vulnerabilities older than $overdueThreshold days: $hostsWithOverdue of $totalHosts (${String.format("%.1f", overduePercent)}%)")
            System.out.println("Servers with no vulnerabilities older than $overdueThreshold days: $hostsWithoutOverdue of $totalHosts")

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

            // Import directly to database via import service (hostname-specific queries)
            System.out.println("\nImporting to database...")

            // Map CrowdStrike DTOs to backend batch DTOs
            val batches = vulnerabilitiesByHostname.map { (hostname, vulns) ->
                val firstVuln = vulns.firstOrNull()
                // Feature 082: Use cloudInstanceId from the most recently detected vulnerability
                val latestCloudInstanceId = vulns
                    .filter { !it.cloudInstanceId.isNullOrBlank() }
                    .maxByOrNull { it.detectedAt }
                    ?.cloudInstanceId
                CrowdStrikeVulnerabilityBatchDto(
                    hostname = hostname,
                    groups = null,
                    cloudAccountId = firstVuln?.cloudAccountId,
                    cloudInstanceId = latestCloudInstanceId ?: firstVuln?.cloudInstanceId,
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

            // Count systems with overdue vulnerabilities (O(1) extra memory - just a counter)
            val systemsWithOverdueVulns = batches.count { batch ->
                batch.vulnerabilities.any { it.daysOpen > overdueThreshold }
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

            // Vulnerability age summary
            if (result.serversProcessed > 0) {
                val totalWithoutOverdue = result.serversProcessed - systemsWithOverdueVulns
                val percent = systemsWithOverdueVulns * 100.0 / result.serversProcessed
                System.out.println("\n--- Vulnerability Age Summary (>$overdueThreshold days) ---")
                System.out.println("Servers with vulnerabilities older than $overdueThreshold days: $systemsWithOverdueVulns of ${result.serversProcessed} (${String.format("%.1f", percent)}%)")
                System.out.println("Servers with no vulnerabilities older than $overdueThreshold days: $totalWithoutOverdue of ${result.serversProcessed}")
            }

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

    private fun logMemoryUsage(label: String) {
        if (!verbose) return
        val runtime = Runtime.getRuntime()
        val usedMB = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
        val maxMB = runtime.maxMemory() / (1024 * 1024)
        System.out.println("  [Memory $label] ${usedMB}MB / ${maxMB}MB (${usedMB * 100 / maxMB}%)")
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
