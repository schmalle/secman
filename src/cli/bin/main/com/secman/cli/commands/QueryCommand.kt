package com.secman.cli.commands

import com.secman.cli.config.ConfigLoader
import com.secman.cli.export.ExportService
import com.secman.crowdstrike.auth.CrowdStrikeAuthService
import com.secman.crowdstrike.client.CrowdStrikeApiClient
import com.secman.crowdstrike.client.CrowdStrikeApiClientImpl
import com.secman.crowdstrike.dto.FalconConfigDto
import io.micronaut.context.ApplicationContext
import io.micronaut.http.client.HttpClient
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Query command for CrowdStrike vulnerabilities
 *
 * Usage (future with Picocli):
 *   secman query <hostname> [options]
 *
 * Functionality:
 * - Query CrowdStrike Falcon API for vulnerabilities
 * - Filter by severity and product
 * - Support pagination
 * - Export to JSON or CSV
 *
 * Related to: Feature 023-create-in-the
 * Task: T049, T057-T060
 */
class QueryCommand {
    private val log = LoggerFactory.getLogger(QueryCommand::class.java)
    private val configLoader = ConfigLoader()
    private val exportService = ExportService()
    private val appContext = ApplicationContext.run()
    private val apiClient: CrowdStrikeApiClient = appContext.getBean(CrowdStrikeApiClient::class.java)

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

            // Filter by severity if specified
            val filteredByServerity = if (severity != null) {
                response.copy(
                    vulnerabilities = response.vulnerabilities.filter {
                        it.severity.lowercase() == severity!!.lowercase()
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
