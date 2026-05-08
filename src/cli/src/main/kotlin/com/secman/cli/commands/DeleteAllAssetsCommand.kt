package com.secman.cli.commands

import com.secman.cli.service.CliHttpClient
import jakarta.inject.Inject
import jakarta.inject.Singleton
import picocli.CommandLine.Command
import picocli.CommandLine.Option

/**
 * CLI command to delete EVERY asset and cascade-delete related rows
 * (vulnerabilities, scan results, scan ports). Requires ADMIN role and the explicit
 * --confirm flag.
 *
 * Wraps DELETE /api/assets/bulk.
 */
@Singleton
@Command(
    name = "delete-all-assets",
    description = ["Delete ALL assets with cascade deletion (ADMIN role + --confirm required). DESTRUCTIVE."],
    mixinStandardHelpOptions = true
)
class DeleteAllAssetsCommand : Runnable {

    @Inject
    lateinit var cliHttpClient: CliHttpClient

    @Option(names = ["--confirm"], description = ["Required safety flag. Without --confirm, the command refuses to run."])
    var confirm: Boolean = false

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
            if (!confirm) {
                throw IllegalArgumentException(
                    "Refusing to delete all assets without --confirm. " +
                    "This is a destructive operation that cannot be undone."
                )
            }

            val effectiveUrl = getEffectiveBackendUrl()
            val effectiveUsername = getEffectiveUsername()
            val effectivePassword = getEffectivePassword()

            println("============================================================")
            println("Delete ALL Assets (BULK)")
            println("============================================================")
            println("This will delete every asset and cascade-delete:")
            println("  - vulnerabilities")
            println("  - scan results and scan ports")
            println("  - asset-workgroup links")
            println("  - demand.existing_asset_id references (nullified, demand rows preserved)")
            println()

            val authToken = cliHttpClient.authenticate(effectiveUsername, effectivePassword, effectiveUrl)
                ?: throw RuntimeException("Authentication failed. Check credentials.")

            val (status, body) = cliHttpClient.deleteMapWithStatus(
                "$effectiveUrl/api/assets/bulk",
                authToken
            )

            if (status == 409) {
                throw RuntimeException(
                    "Another bulk delete operation is already in progress. Wait for it to finish, then retry."
                )
            }
            if (status !in 200..299) {
                val message = body?.get("message")?.toString()
                    ?: body?.get("error")?.toString()
                    ?: "Backend returned HTTP $status"
                throw RuntimeException("Bulk delete failed (HTTP $status): $message")
            }

            val deletedAssets = (body?.get("deletedAssets") as? Number)?.toInt() ?: 0
            val deletedVulns = (body?.get("deletedVulnerabilities") as? Number)?.toInt() ?: 0
            val deletedScanResults = (body?.get("deletedScanResults") as? Number)?.toInt() ?: 0
            val message = body?.get("message")?.toString() ?: ""

            println("Bulk delete complete:")
            println("  Assets deleted:        $deletedAssets")
            println("  Vulnerabilities:       $deletedVulns")
            println("  Scan results:          $deletedScanResults")
            if (message.isNotBlank()) {
                println("  Message:               $message")
            }
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
