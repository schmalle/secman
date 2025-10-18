package com.secman.cli.export

import com.secman.crowdstrike.dto.CrowdStrikeQueryResponse
import com.secman.crowdstrike.dto.CrowdStrikeVulnerabilityDto
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVPrinter
import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileWriter

/**
 * Export service for vulnerability data
 *
 * Supports exporting to:
 * - JSON format (pretty-printed)
 * - CSV format (with header row)
 *
 * Features:
 * - File overwrite prompt (interactive mode)
 * - Automatic directory creation
 * - Write permission validation
 *
 * Related to: Feature 023-create-in-the (Phase 6: Export Results)
 * Task: T101-T110
 */
class ExportService {
    private val log = LoggerFactory.getLogger(ExportService::class.java)

    /**
     * Export query response to JSON file with overwrite handling
     *
     * Task: T101, T103, T108
     *
     * @param response Query response to export
     * @param outputFile Target JSON file path
     * @param promptOverwrite If true, prompts user before overwriting existing file
     * @return true if exported successfully, false if cancelled by user
     */
    fun exportToJson(
        response: CrowdStrikeQueryResponse,
        outputFile: File,
        promptOverwrite: Boolean = true
    ): Boolean {
        require(response.vulnerabilities.isNotEmpty()) { "Cannot export empty vulnerability list" }

        try {
            // Ensure parent directory exists
            if (!ensureDirectoryExists(outputFile.parentFile)) {
                throw RuntimeException("Failed to create parent directory: ${outputFile.parentFile.absolutePath}")
            }

            // Check file overwrite
            if (outputFile.exists() && promptOverwrite) {
                if (!promptFileOverwrite(outputFile)) {
                    log.info("Export cancelled by user")
                    return false
                }
            }

            // Validate write permission
            if (!canWriteToFile(outputFile)) {
                throw RuntimeException("No write permission for file: ${outputFile.absolutePath}")
            }

            log.info("Exporting {} vulnerabilities to JSON: {}", response.vulnerabilities.size, outputFile.absolutePath)

            val jsonContent = buildJsonString(response)
            outputFile.writeText(jsonContent)

            log.info("Successfully exported to: {}", outputFile.absolutePath)
            return true
        } catch (e: Exception) {
            log.error("Failed to export to JSON: {}", e.message, e)
            throw RuntimeException("Failed to export to JSON: ${e.message}", e)
        }
    }

    /**
     * Export query response to CSV file with overwrite handling
     *
     * Task: T102, T104, T105, T108
     *
     * CSV columns:
     * - Hostname
     * - CVE ID
     * - Severity
     * - CVSS Score
     * - Affected Product
     * - Days Open
     * - Detected At
     * - Status
     * - Exception
     *
     * @param response Query response to export
     * @param outputFile Target CSV file path
     * @param promptOverwrite If true, prompts user before overwriting existing file
     * @return true if exported successfully, false if cancelled by user
     */
    fun exportToCsv(
        response: CrowdStrikeQueryResponse,
        outputFile: File,
        promptOverwrite: Boolean = true
    ): Boolean {
        require(response.vulnerabilities.isNotEmpty()) { "Cannot export empty vulnerability list" }

        try {
            // Ensure parent directory exists
            if (!ensureDirectoryExists(outputFile.parentFile)) {
                throw RuntimeException("Failed to create parent directory: ${outputFile.parentFile.absolutePath}")
            }

            // Check file overwrite
            if (outputFile.exists() && promptOverwrite) {
                if (!promptFileOverwrite(outputFile)) {
                    log.info("Export cancelled by user")
                    return false
                }
            }

            // Validate write permission
            if (!canWriteToFile(outputFile)) {
                throw RuntimeException("No write permission for file: ${outputFile.absolutePath}")
            }

            log.info("Exporting {} vulnerabilities to CSV: {}", response.vulnerabilities.size, outputFile.absolutePath)

            // Use streaming to avoid loading entire dataset in memory
            FileWriter(outputFile).use { writer ->
                val csvFormat = CSVFormat.Builder.create(CSVFormat.DEFAULT)
                    .setHeader(
                        "Hostname",
                        "CVE ID",
                        "Severity",
                        "CVSS Score",
                        "Affected Product",
                        "Days Open",
                        "Detected At",
                        "Status",
                        "Exception"
                    )
                    .build()

                CSVPrinter(writer, csvFormat).use { printer ->
                    for (vuln in response.vulnerabilities) {
                        printer.printRecord(
                            vuln.hostname,
                            vuln.cveId ?: "",
                            vuln.severity,
                            vuln.cvssScore?.toString() ?: "",
                            vuln.affectedProduct ?: "",
                            vuln.daysOpen ?: "",
                            vuln.detectedAt,
                            vuln.status,
                            if (vuln.hasException) vuln.exceptionReason ?: "Yes" else "No"
                        )
                    }
                }
            }

            log.info("Successfully exported to: {}", outputFile.absolutePath)
            return true
        } catch (e: Exception) {
            log.error("Failed to export to CSV: {}", e.message, e)
            throw RuntimeException("Failed to export to CSV: ${e.message}", e)
        }
    }

    /**
     * Export query response to both JSON and CSV
     *
     * Task: T048
     *
     * @param response Query response to export
     * @param baseFilename Base filename without extension
     * @param outputDir Output directory
     * @param promptOverwrite If true, prompts user before overwriting existing files
     * @return Pair of (JSON file, CSV file) or null if cancelled
     */
    fun exportToMultiple(
        response: CrowdStrikeQueryResponse,
        baseFilename: String,
        outputDir: File,
        promptOverwrite: Boolean = true
    ): Pair<File, File>? {
        require(outputDir.exists() && outputDir.isDirectory) { "Output directory must exist: ${outputDir.absolutePath}" }
        require(response.vulnerabilities.isNotEmpty()) { "Cannot export empty vulnerability list" }

        val jsonFile = File(outputDir, "$baseFilename.json")
        val csvFile = File(outputDir, "$baseFilename.csv")

        val jsonSuccess = exportToJson(response, jsonFile, promptOverwrite)
        if (!jsonSuccess) {
            return null
        }

        val csvSuccess = exportToCsv(response, csvFile, promptOverwrite)
        if (!csvSuccess) {
            return null
        }

        log.info("Exported to JSON: {} and CSV: {}", jsonFile.absolutePath, csvFile.absolutePath)

        return Pair(jsonFile, csvFile)
    }

    /**
     * Ensure directory exists, creating it if necessary
     *
     * Task: T109
     *
     * @param dir Directory to check/create
     * @return true if directory exists or was created successfully
     */
    private fun ensureDirectoryExists(dir: File): Boolean {
        if (dir.exists()) {
            return dir.isDirectory
        }

        return try {
            val created = dir.mkdirs()
            if (created) {
                log.info("Created directory: {}", dir.absolutePath)
            }
            created
        } catch (e: Exception) {
            log.error("Failed to create directory: {}", dir.absolutePath, e)
            false
        }
    }

    /**
     * Check if file can be written to
     *
     * Task: T110
     *
     * @param file File to check
     * @return true if file can be written
     */
    private fun canWriteToFile(file: File): Boolean {
        return if (file.exists()) {
            file.canWrite()
        } else {
            // Check if parent directory is writable
            file.parentFile?.canWrite() ?: false
        }
    }

    /**
     * Prompt user for file overwrite confirmation
     *
     * Task: T108
     *
     * @param file File that would be overwritten
     * @return true if user confirms overwrite, false otherwise
     */
    private fun promptFileOverwrite(file: File): Boolean {
        print("File exists: ${file.absolutePath}. Overwrite? (y/n): ")
        val response = readlnOrNull()?.trim()?.lowercase()
        return response == "y" || response == "yes"
    }

    /**
     * Build JSON string representation of query response
     */
    private fun buildJsonString(response: CrowdStrikeQueryResponse): String {
        val sb = StringBuilder()
        sb.append("{\n")
        sb.append("  \"hostname\": \"${escapeJson(response.hostname)}\",\n")
        sb.append("  \"queriedAt\": \"${response.queriedAt}\",\n")
        sb.append("  \"totalCount\": ${response.totalCount},\n")
        sb.append("  \"vulnerabilities\": [\n")

        response.vulnerabilities.forEachIndexed { index, vuln ->
            sb.append("    {\n")
            sb.append("      \"id\": \"${escapeJson(vuln.id)}\",\n")
            val cveIdValue = vuln.cveId
            sb.append("      \"cveId\": ${if (cveIdValue != null) "\"${escapeJson(cveIdValue)}\"" else "null"},\n")
            sb.append("      \"severity\": \"${vuln.severity}\",\n")
            sb.append("      \"cvssScore\": ${vuln.cvssScore ?: "null"},\n")
            val affectedProductValue = vuln.affectedProduct
            sb.append("      \"affectedProduct\": ${if (affectedProductValue != null) "\"${escapeJson(affectedProductValue)}\"" else "null"},\n")
            val daysOpenValue = vuln.daysOpen
            sb.append("      \"daysOpen\": ${if (daysOpenValue != null) "\"${escapeJson(daysOpenValue)}\"" else "null"},\n")
            sb.append("      \"detectedAt\": \"${vuln.detectedAt}\",\n")
            sb.append("      \"status\": \"${vuln.status}\",\n")
            sb.append("      \"hasException\": ${vuln.hasException},\n")
            val exceptionReasonValue = vuln.exceptionReason
            sb.append("      \"exceptionReason\": ${if (exceptionReasonValue != null) "\"${escapeJson(exceptionReasonValue)}\"" else "null"}\n")
            sb.append("    }")
            if (index < response.vulnerabilities.size - 1) {
                sb.append(",")
            }
            sb.append("\n")
        }

        sb.append("  ]\n")
        sb.append("}\n")

        return sb.toString()
    }

    /**
     * Escape JSON special characters
     */
    private fun escapeJson(str: String): String {
        return str
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }
}
