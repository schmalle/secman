package com.secman.cli.commands

import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpResponse
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import jakarta.inject.Inject
import org.slf4j.LoggerFactory
import picocli.CommandLine.Command
import picocli.CommandLine.Option

/**
 * CLI command to deduplicate vulnerability records in the database.
 *
 * Scans all assets and removes duplicate vulnerability entries where the same
 * (vulnerability_id, vulnerable_product_versions) combination appears more than
 * once for the same asset. The oldest record (lowest primary key) is kept.
 *
 * Requires ADMIN role.
 *
 * Usage:
 *   secman deduplicate-vulnerabilities [options]
 *
 * Examples:
 *   secman deduplicate-vulnerabilities --username admin --password secret
 *   secman deduplicate-vulnerabilities --verbose
 *   secman deduplicate-vulnerabilities --backend-url http://prod:8080
 */
@Command(
    name = "deduplicate-vulnerabilities",
    description = [
        "Remove duplicate vulnerability records from the database.",
        "",
        "Scans all assets and removes entries where the same (CVE ID, product)",
        "combination appears more than once per asset. Keeps the oldest record.",
        "Requires ADMIN role."
    ],
    mixinStandardHelpOptions = true
)
class DeduplicateVulnerabilitiesCommand : Runnable {

    private val log = LoggerFactory.getLogger(DeduplicateVulnerabilitiesCommand::class.java)

    @Inject
    @Client("\${secman.backend.base-url:http://localhost:8080}")
    lateinit var httpClient: HttpClient

    @Option(
        names = ["--backend-url"],
        description = ["Backend API URL (default: http://localhost:8080)"],
        defaultValue = "http://localhost:8080"
    )
    var backendUrl: String = "http://localhost:8080"

    @Option(
        names = ["--username", "-u"],
        description = ["Backend username (or set SECMAN_USERNAME env var)"],
        required = false
    )
    var username: String? = null

    @Option(
        names = ["--password", "-p"],
        description = ["Backend password (or set SECMAN_PASSWORD env var)"],
        required = false
    )
    var password: String? = null

    @Option(
        names = ["--verbose", "-v"],
        description = ["Enable verbose output showing per-asset details"]
    )
    var verbose: Boolean = false

    override fun run() {
        // Resolve credentials
        val effectiveUsername = username
            ?: System.getenv("SECMAN_USERNAME")
            ?: run {
                System.err.println("Error: Username required via --username or SECMAN_USERNAME env var")
                System.exit(1)
                return
            }

        val effectivePassword = password
            ?: System.getenv("SECMAN_PASSWORD")
            ?: run {
                System.err.println("Error: Password required via --password or SECMAN_PASSWORD env var")
                System.exit(1)
                return
            }

        println("============================================================")
        println("Deduplicate Vulnerabilities")
        println("============================================================")
        println()

        // Step 1: Authenticate
        println("Authenticating with backend...")
        val token = authenticate(effectiveUsername, effectivePassword)
        if (token == null) {
            System.err.println("Error: Authentication failed")
            System.err.println("   Check username and password. ADMIN role is required.")
            System.exit(1)
            return
        }
        println("Authentication successful")
        println()

        // Step 2: Call deduplication endpoint
        println("Running deduplication...")
        println()

        try {
            val endpoint = "$backendUrl/api/vulnerabilities/deduplicate"
            val request = HttpRequest.POST(endpoint, "")
                .contentType(io.micronaut.http.MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer $token")

            val response: HttpResponse<Map<*, *>> = httpClient.toBlocking()
                .exchange(request, Map::class.java)

            if (response.status.code == 200) {
                val body = response.body() as? Map<*, *>
                val totalRemoved = (body?.get("totalDuplicatesRemoved") as? Number)?.toInt() ?: 0
                val assetsAffected = (body?.get("assetsAffected") as? Number)?.toInt() ?: 0

                println("============================================================")
                println("Deduplication Results")
                println("============================================================")
                println("Total duplicates removed: $totalRemoved")
                println("Assets affected: $assetsAffected")

                if (totalRemoved == 0) {
                    println()
                    println("No duplicate vulnerabilities found. Database is clean.")
                }

                // Show per-asset details in verbose mode
                @Suppress("UNCHECKED_CAST")
                val details = body?.get("details") as? List<Map<*, *>>
                if (verbose && details != null && details.isNotEmpty()) {
                    println()
                    println("--- Per-Asset Details ---")
                    for (detail in details) {
                        val assetId = detail["assetId"]
                        val assetName = detail["assetName"] ?: "N/A"
                        val removed = detail["duplicatesRemoved"]
                        val keys = (detail["duplicateKeys"] as? List<*>)?.joinToString(", ") ?: ""
                        println("  Asset $assetId ($assetName): $removed duplicates removed")
                        if (keys.isNotBlank()) {
                            println("    CVEs: $keys")
                        }
                    }
                }

                println()
            } else {
                System.err.println("Error: Backend returned status ${response.status.code}")
                System.exit(1)
            }
        } catch (e: io.micronaut.http.client.exceptions.HttpClientResponseException) {
            val body = try {
                e.response.getBody(Map::class.java).orElse(null)
            } catch (ex: Exception) { null }
            val errorDetail = body?.get("error")?.toString() ?: e.message
            System.err.println("Error: Backend API error ${e.status.code} - $errorDetail")
            if (e.status.code == 403) {
                System.err.println("   ADMIN role is required for deduplication.")
            }
            if (verbose) {
                e.printStackTrace()
            }
            System.exit(1)
        } catch (e: java.net.ConnectException) {
            System.err.println("Error: Cannot connect to backend at $backendUrl")
            System.err.println("   Ensure the backend is running and the URL is correct.")
            System.exit(2)
        } catch (e: Exception) {
            System.err.println("Error: [${e.javaClass.simpleName}] ${e.message}")
            if (verbose) {
                e.printStackTrace()
            }
            System.exit(1)
        }
    }

    private fun authenticate(username: String, password: String): String? {
        try {
            val endpoint = "$backendUrl/api/auth/login"
            val request = HttpRequest.POST(endpoint, mapOf(
                "username" to username,
                "password" to password
            )).contentType(io.micronaut.http.MediaType.APPLICATION_JSON)

            val response: HttpResponse<Map<*, *>> = httpClient.toBlocking()
                .exchange(request, Map::class.java)

            if (response.status.code == 200) {
                val body = response.body() as? Map<*, *>
                return body?.get("access_token")?.toString()
                    ?: body?.get("token")?.toString()
                    ?: body?.get("accessToken")?.toString()
            }
            return null
        } catch (e: Exception) {
            log.error("Authentication error: {}", e.message, e)
            return null
        }
    }
}
