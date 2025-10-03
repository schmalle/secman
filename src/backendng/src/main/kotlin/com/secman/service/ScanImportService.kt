package com.secman.service

import com.secman.domain.Asset
import com.secman.domain.Scan
import com.secman.domain.ScanPort
import com.secman.domain.ScanResult
import com.secman.repository.AssetRepository
import com.secman.repository.ScanRepository
import jakarta.inject.Singleton
import jakarta.transaction.Transactional
import org.slf4j.LoggerFactory
import java.time.LocalDateTime

/**
 * Service for importing scan files and creating assets
 *
 * Coordinates the import process:
 * 1. Parse nmap XML (via NmapParserService)
 * 2. Lookup/create Assets by IP
 * 3. Create Scan/ScanResult/ScanPort records
 * 4. Handle duplicates and errors
 * 5. Audit logging
 *
 * Related to:
 * - Feature: 002-implement-a-parsing (Nmap Scan Import)
 * - FR-002: Automatically create assets
 * - FR-012: IP as asset name when hostname missing (Decision 1)
 * - FR-013: Skip duplicate IPs (Decision 2)
 * - FR-014: Default type "Network Host" (Decision 3)
 * - FR-015: Point-in-time snapshots (Decision 4)
 * - NFR-003: Audit logging
 */
@Singleton
open class ScanImportService(
    private val nmapParserService: NmapParserService,
    private val scanRepository: ScanRepository,
    private val assetRepository: AssetRepository
) {
    private val logger = LoggerFactory.getLogger(ScanImportService::class.java)

    /**
     * Import nmap scan file
     *
     * @param xmlContent Raw XML file content
     * @param filename Original filename
     * @param username User uploading the scan
     * @return Import summary with statistics
     */
    @Transactional
    open fun importNmapScan(
        xmlContent: ByteArray,
        filename: String,
        username: String
    ): ScanImportSummary {
        val startTime = System.currentTimeMillis()

        try {
            logger.info("Starting nmap import: filename=$filename, user=$username, size=${xmlContent.size} bytes")

            // Parse nmap XML
            val nmapData = nmapParserService.parseNmapXml(xmlContent)
            logger.debug("Parsed nmap XML: ${nmapData.hosts.size} hosts found")

            // Create Scan record
            val scan = createScanRecord(nmapData, filename, username)
            logger.info("Created Scan record: id=${scan.id}, hosts=${nmapData.hosts.size}")

            // Process each host
            val importStats = processHosts(scan, nmapData.hosts, username)

            // Update scan with final host count
            scan.hostCount = importStats.assetsCreated + importStats.assetsUpdated
            scanRepository.update(scan)

            val duration = (System.currentTimeMillis() - startTime) / 1000
            logger.info("Nmap import completed: scanId=${scan.id}, duration=${duration}s, stats=$importStats")

            // Audit log successful import
            logAuditEvent(
                scanId = scan.id!!,
                action = "IMPORT",
                username = username,
                message = "Nmap scan imported: $filename (${importStats.assetsCreated + importStats.assetsUpdated} hosts, ${importStats.totalPorts} ports)"
            )

            return ScanImportSummary(
                scanId = scan.id!!,
                filename = filename,
                scanDate = scan.scanDate,
                hostsDiscovered = nmapData.hosts.size,
                assetsCreated = importStats.assetsCreated,
                assetsUpdated = importStats.assetsUpdated,
                totalPorts = importStats.totalPorts,
                duplicatesSkipped = importStats.duplicatesSkipped,
                duration = formatDuration(duration.toInt())
            )

        } catch (e: NmapParseException) {
            logger.error("Nmap parse error: ${e.message}", e)
            logAuditEvent(
                scanId = null,
                action = "IMPORT_FAILED",
                username = username,
                message = "Nmap parse failed for $filename: ${e.message}"
            )
            throw e
        } catch (e: Exception) {
            logger.error("Nmap import failed: ${e.message}", e)
            logAuditEvent(
                scanId = null,
                action = "IMPORT_FAILED",
                username = username,
                message = "Nmap import failed for $filename: ${e.message}"
            )
            throw ScanImportException("Scan import failed: ${e.message}", e)
        }
    }

    /**
     * Create Scan entity from parsed data
     */
    private fun createScanRecord(nmapData: NmapScanData, filename: String, username: String): Scan {
        val scan = Scan(
            scanType = "nmap",
            filename = filename,
            scanDate = nmapData.scanDate,
            uploadedBy = username,
            hostCount = nmapData.hosts.size,
            duration = nmapData.duration
        )

        return scanRepository.save(scan)
    }

    /**
     * Process all hosts from scan
     * Implements duplicate detection (Decision 2)
     */
    private fun processHosts(scan: Scan, hosts: List<NmapHost>, username: String): ImportStats {
        val stats = ImportStats()
        val seenIps = mutableSetOf<String>()

        for (host in hosts) {
            // Check for duplicate IP within same scan (Decision 2)
            if (seenIps.contains(host.ipAddress)) {
                logger.warn("Duplicate IP detected in scan ${scan.id}: ${host.ipAddress}, skipping")
                logAuditEvent(
                    scanId = scan.id!!,
                    action = "DUPLICATE_IP",
                    username = username,
                    message = "Skipped duplicate IP ${host.ipAddress} in scan ${scan.id}",
                    level = "WARN"
                )
                stats.duplicatesSkipped++
                continue
            }

            seenIps.add(host.ipAddress)

            try {
                val isNewAsset = processHost(scan, host, username)
                if (isNewAsset) {
                    stats.assetsCreated++
                } else {
                    stats.assetsUpdated++
                }
                stats.totalPorts += host.ports.size

            } catch (e: Exception) {
                logger.error("Failed to process host ${host.ipAddress}: ${e.message}", e)
                logAuditEvent(
                    scanId = scan.id!!,
                    action = "HOST_PROCESS_ERROR",
                    username = username,
                    message = "Failed to process host ${host.ipAddress}: ${e.message}",
                    level = "ERROR"
                )
                // Continue processing other hosts
            }
        }

        return stats
    }

    /**
     * Process a single host: lookup/create asset, create scan result
     * Returns: true if new asset created, false if existing asset updated
     */
    private fun processHost(scan: Scan, host: NmapHost, username: String): Boolean {
        // Lookup asset by IP
        val asset = lookupOrCreateAsset(host, username)
        val isNewAsset = asset.createdAt == asset.updatedAt

        // Create ScanResult
        val scanResult = ScanResult(
            scan = scan,
            asset = asset,
            ipAddress = host.ipAddress,
            hostname = host.hostname,
            discoveredAt = scan.scanDate
        )

        // Add ports to scan result
        for (port in host.ports) {
            val scanPort = ScanPort(
                scanResult = scanResult,
                portNumber = port.portNumber,
                protocol = port.protocol,
                state = port.state,
                service = port.service,
                version = port.version
            )
            scanResult.addPort(scanPort)
        }

        // Add result to scan and asset
        scan.addResult(scanResult)
        asset.addScanResult(scanResult)

        // Update asset last_seen
        asset.lastSeen = scan.scanDate
        assetRepository.update(asset)

        // Audit log asset creation
        if (isNewAsset) {
            logAuditEvent(
                scanId = scan.id!!,
                action = "ASSET_CREATED",
                username = username,
                message = "Created asset from scan: ${asset.name} (${asset.ip})"
            )
        }

        return isNewAsset
    }

    /**
     * Lookup asset by IP or create new one
     * Implements:
     * - Decision 1: IP as name when hostname missing
     * - Decision 3: Default type "Network Host"
     */
    private fun lookupOrCreateAsset(host: NmapHost, username: String): Asset {
        // Try to find existing asset by IP
        val existing = assetRepository.findByIp(host.ipAddress).firstOrNull()

        if (existing != null) {
            logger.debug("Found existing asset for IP ${host.ipAddress}: id=${existing.id}")
            return existing
        }

        // Create new asset
        val assetName = host.hostname ?: host.ipAddress // Decision 1
        val asset = Asset(
            name = assetName,
            type = "Network Host", // Decision 3
            ip = host.ipAddress,
            owner = username,
            description = "Imported from nmap scan on ${LocalDateTime.now().toLocalDate()}"
        )

        val saved = assetRepository.save(asset)
        logger.info("Created new asset: id=${saved.id}, name=$assetName, ip=${host.ipAddress}")

        return saved
    }

    /**
     * Format duration for display
     * Examples: "5s", "2m 30s"
     */
    private fun formatDuration(seconds: Int): String {
        return if (seconds < 60) {
            "${seconds}s"
        } else {
            val minutes = seconds / 60
            val remainingSeconds = seconds % 60
            "${minutes}m ${remainingSeconds}s"
        }
    }

    /**
     * Log audit event
     * Implements: NFR-003 (audit logging)
     *
     * Note: This is a placeholder implementation.
     * In production, this should write to a dedicated audit_log table.
     */
    private fun logAuditEvent(
        scanId: Long?,
        action: String,
        username: String,
        message: String,
        level: String = "INFO"
    ) {
        when (level) {
            "WARN" -> logger.warn("[AUDIT] scanId=$scanId, action=$action, user=$username, message=$message")
            "ERROR" -> logger.error("[AUDIT] scanId=$scanId, action=$action, user=$username, message=$message")
            else -> logger.info("[AUDIT] scanId=$scanId, action=$action, user=$username, message=$message")
        }

        // TODO: Write to audit_log table
        // This requires an AuditLog entity and repository (future enhancement)
    }
}

/**
 * Import statistics (internal tracking)
 */
private data class ImportStats(
    var assetsCreated: Int = 0,
    var assetsUpdated: Int = 0,
    var totalPorts: Int = 0,
    var duplicatesSkipped: Int = 0
)

/**
 * Scan import summary (returned to caller)
 */
data class ScanImportSummary(
    val scanId: Long,
    val filename: String,
    val scanDate: LocalDateTime,
    val hostsDiscovered: Int,
    val assetsCreated: Int,
    val assetsUpdated: Int,
    val totalPorts: Int,
    val duplicatesSkipped: Int,
    val duration: String
)

/**
 * Exception thrown when scan import fails
 */
class ScanImportException(message: String, cause: Throwable? = null) : Exception(message, cause)
