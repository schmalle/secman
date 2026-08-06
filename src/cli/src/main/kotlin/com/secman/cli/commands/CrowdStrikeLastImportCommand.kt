package com.secman.cli.commands

import com.secman.cli.service.CliHttpClient
import jakarta.inject.Inject
import picocli.CommandLine.Command
import picocli.CommandLine.Option

@Command(
    name = "crowdstrike-last-import",
    description = ["Show the timestamp and metadata of the most recent CrowdStrike vulnerability import"],
    mixinStandardHelpOptions = true
)
class CrowdStrikeLastImportCommand : Runnable {

    @Inject
    lateinit var cliHttpClient: CliHttpClient

    @Option(names = ["--username"], description = ["Backend username (or set SECMAN_ADMIN_NAME env var)"])
    var username: String? = null

    @Option(names = ["--password"], description = ["Backend password (or set SECMAN_ADMIN_PASS env var)"])
    var password: String? = null

    @Option(names = ["--backend-url"], description = ["Backend API URL (or set SECMAN_HOST / SECMAN_BACKEND_URL env var)"])
    var backendUrl: String? = null

    @Option(names = ["--format"], description = ["Output format: text (default) or json"])
    var format: String = "text"

    @Option(
        names = ["--insecure"],
        description = ["Disable SSL certificate verification (accept self-signed certs). Can also set SECMAN_INSECURE=true"]
    )
    var insecure: Boolean = false

    @Option(names = ["--verbose", "-v"], description = ["Enable verbose output"])
    var verbose: Boolean = false

    override fun run() {
        try {
            val effectiveUrl = resolveBackendUrl()
            val effectiveUsername = resolveUsername()
            val effectivePassword = resolvePassword()

            if (verbose) {
                System.err.println("Backend URL: $effectiveUrl")
                System.err.println("Username   : $effectiveUsername")
            }

            val authToken = cliHttpClient.authenticate(effectiveUsername, effectivePassword, effectiveUrl)
                ?: throw RuntimeException(
                    "Authentication failed (HTTP 401 / invalid credentials). " +
                    "Re-run with --verbose to see exactly which username and backend URL were used."
                )

            val url = "$effectiveUrl/api/crowdstrike/servers/import/latest"
            val body = cliHttpClient.getMap(url, authToken)

            if (format.equals("json", ignoreCase = true)) {
                if (body == null) {
                    println("null")
                } else {
                    println(cliHttpClient.getObjectMapper().writeValueAsString(body))
                }
                return
            }

            // Text format
            if (body == null) {
                println("Last CrowdStrike import: never")
                return
            }

            val importedAt = body["importedAt"]?.toString() ?: "unknown"
            val importedBy = body["importedBy"]?.toString() ?: "(unknown)"
            val serversProcessed = body["serversProcessed"] ?: 0
            val serversCreated = body["serversCreated"] ?: 0
            val serversUpdated = body["serversUpdated"] ?: 0
            val vulnerabilitiesImported = body["vulnerabilitiesImported"] ?: 0
            val vulnerabilitiesSkipped = body["vulnerabilitiesSkipped"] ?: 0
            val vulnerabilitiesWithPatchDate = body["vulnerabilitiesWithPatchDate"] ?: 0
            val errorCount = body["errorCount"] ?: 0

            println("============================================================")
            println("CrowdStrike Last Import")
            println("============================================================")
            println("Imported at                     : $importedAt")
            println("Imported by                     : $importedBy")
            println("Servers processed               : $serversProcessed")
            println("Servers created                 : $serversCreated")
            println("Servers updated                 : $serversUpdated")
            println("Vulnerabilities imported        : $vulnerabilitiesImported")
            println("Vulnerabilities skipped         : $vulnerabilitiesSkipped")
            println("Vulnerabilities with patch date : $vulnerabilitiesWithPatchDate")
            println("Error count                     : $errorCount")
        } catch (e: Exception) {
            System.err.println("Error: ${e.message}")
            if (verbose) {
                e.printStackTrace()
            }
            System.exit(1)
        }
    }

    private fun resolveUsername(): String {
        username?.let { return it }
        System.getenv("SECMAN_ADMIN_NAME")?.let { return it }
        throw IllegalArgumentException(
            "Backend username required. Use --username flag or set SECMAN_ADMIN_NAME environment variable"
        )
    }

    private fun resolvePassword(): String {
        password?.let { return it }
        System.getenv("SECMAN_ADMIN_PASS")?.let { return it }
        throw IllegalArgumentException(
            "Backend password required. Use --password flag or set SECMAN_ADMIN_PASS environment variable"
        )
    }

    private fun resolveBackendUrl(): String {
        val raw = backendUrl
            ?: System.getenv("SECMAN_HOST")
            ?: System.getenv("SECMAN_BACKEND_URL")
            ?: "http://localhost:8080"
        return if (raw.startsWith("http://") || raw.startsWith("https://")) raw else "https://$raw"
    }
}
