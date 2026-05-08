package com.secman.cli.commands

import com.secman.cli.service.CliHttpClient
import jakarta.inject.Inject
import jakarta.inject.Singleton
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters

/**
 * CLI command to delete a single asset by ID with cascade deletion of related rows
 * (vulnerabilities, exception requests, ASSET-type vulnerability exceptions).
 *
 * Wraps DELETE /api/assets/{id}. Requires ADMIN role.
 */
@Singleton
@Command(
    name = "delete-asset",
    description = ["Delete a single asset by ID with cascade deletion (ADMIN role required)"],
    mixinStandardHelpOptions = true
)
class DeleteAssetCommand : Runnable {

    @Inject
    lateinit var cliHttpClient: CliHttpClient

    @Parameters(index = "0", description = ["Asset ID to delete"])
    var assetId: Long = 0

    @Option(names = ["--force-timeout"], description = ["Force deletion even if it may exceed the server timeout estimate"])
    var forceTimeout: Boolean = false

    @Option(names = ["--username"], description = ["Backend username (or set SECMAN_ADMIN_NAME env var)"])
    var username: String? = null

    @Option(names = ["--password"], description = ["Backend password (or set SECMAN_ADMIN_PASS env var)"])
    var password: String? = null

    @Option(names = ["--backend-url"], description = ["Backend API URL (or set SECMAN_HOST/SECMAN_BACKEND_URL env var)"])
    var backendUrl: String? = null

    @Option(names = ["--verbose", "-v"], description = ["Verbose output"])
    var verbose: Boolean = false

    override fun run() {
        try {
            if (assetId <= 0) {
                throw IllegalArgumentException("Asset ID must be a positive integer")
            }

            val effectiveUrl = getEffectiveBackendUrl()
            val effectiveUsername = getEffectiveUsername()
            val effectivePassword = getEffectivePassword()

            println("============================================================")
            println("Delete Asset")
            println("============================================================")
            println("Asset ID:      $assetId")
            println("Force timeout: $forceTimeout")
            println()

            val authToken = cliHttpClient.authenticate(effectiveUsername, effectivePassword, effectiveUrl)
                ?: throw RuntimeException("Authentication failed. Check credentials.")

            val query = if (forceTimeout) "?forceTimeout=true" else ""
            val url = "$effectiveUrl/api/assets/$assetId$query"

            val (status, body) = cliHttpClient.deleteMapWithStatus(url, authToken)

            if (status !in 200..299) {
                val message = body?.get("message")?.toString()
                    ?: body?.get("error")?.toString()
                    ?: "Backend returned HTTP $status"
                throw RuntimeException("Delete failed (HTTP $status): $message")
            }

            val deletedVulns = (body?.get("deletedVulnerabilities") as? Number)?.toInt() ?: 0
            val deletedExceptions = (body?.get("deletedExceptions") as? Number)?.toInt() ?: 0
            val deletedRequests = (body?.get("deletedRequests") as? Number)?.toInt() ?: 0
            val assetName = body?.get("assetName")?.toString()
                ?: body?.get("name")?.toString()
                ?: "Asset #$assetId"

            println("Asset deleted: $assetName (id=$assetId)")
            println("  Vulnerabilities deleted:        $deletedVulns")
            println("  Vulnerability exceptions:       $deletedExceptions")
            println("  Vulnerability exception reqs:   $deletedRequests")
        } catch (e: Exception) {
            System.err.println("Error: ${e.message}")
            if (verbose) {
                e.printStackTrace()
            }
            System.exit(1)
        }
    }

    private fun getEffectiveUsername(): String {
        return username ?: System.getenv("SECMAN_ADMIN_NAME")
            ?: throw IllegalArgumentException("Backend username required. Use --username flag or set SECMAN_ADMIN_NAME environment variable")
    }

    private fun getEffectivePassword(): String {
        return password ?: System.getenv("SECMAN_ADMIN_PASS")
            ?: throw IllegalArgumentException("Backend password required. Use --password flag or set SECMAN_ADMIN_PASS environment variable")
    }

    private fun getEffectiveBackendUrl(): String {
        val url = backendUrl ?: System.getenv("SECMAN_HOST") ?: System.getenv("SECMAN_BACKEND_URL") ?: "http://localhost:8080"
        return if (url.startsWith("http://") || url.startsWith("https://")) url else "https://$url"
    }
}
