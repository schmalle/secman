package com.secman.cli.commands

import com.secman.cli.service.CliHttpClient
import jakarta.inject.Inject
import jakarta.inject.Singleton
import picocli.CommandLine.Command
import picocli.CommandLine.Model
import picocli.CommandLine.Option
import picocli.CommandLine.Spec

/**
 * Downloads the end-of-life (EOL) catalogue into secman and re-matches the
 * inventory against it.
 *
 * The download itself happens **in the backend**, not here — same shape as
 * `import-github-repos`. That keeps the outbound host allowlist, the SSRF
 * checks and the audit record in one place, and means the CLI host does not
 * need internet access to the EOL source.
 *
 * Default source: **endoflife.date** — public, unauthenticated, ~350 products
 * covering the operating systems and platform software this inventory actually
 * contains. Configurable via `secman.eol.base-url` / `secman.eol.allowed-hosts`.
 */
@Singleton
@Command(
    name = "eol-sync",
    description = ["Download the end-of-life catalogue and re-match secman's inventory against it"],
    mixinStandardHelpOptions = true
)
class EolSyncCommand : Runnable {

    @Option(
        names = ["--products"],
        description = ["Comma-separated upstream product keys to refresh (default: the whole catalogue)"]
    )
    var products: String? = null

    @Option(names = ["--no-scan"], description = ["Download the catalogue but do not re-run the matching scan"])
    var noScan: Boolean = false

    @Option(names = ["--scan-only"], description = ["Skip the download; only re-run the matching scan"])
    var scanOnly: Boolean = false

    @Option(
        names = ["--horizon-months"],
        description = ["How far ahead counts as approaching EOL, 1-120 (default: server setting, 12)"]
    )
    var horizonMonths: Int? = null

    @Option(names = ["--verbose", "-v"], description = ["Detailed output (list products that failed to sync)"])
    var verbose: Boolean = false

    @Option(names = ["--username"], description = ["Backend username (or set SECMAN_ADMIN_NAME env var)"])
    var username: String? = null

    @Option(names = ["--password"], description = ["Backend password (or set SECMAN_ADMIN_PASS env var)"])
    var password: String? = null

    @Option(names = ["--backend-url"], description = ["Backend API URL (or set SECMAN_HOST / SECMAN_BACKEND_URL env var)"])
    var backendUrl: String? = null

    @Spec
    lateinit var spec: Model.CommandSpec

    @Inject
    lateinit var cliHttpClient: CliHttpClient

    override fun run() {
        try {
            println("=".repeat(60))
            println("SecMan EOL Catalogue Sync")
            println("=".repeat(60))
            println()

            if (scanOnly && noScan) {
                System.err.println("Error: --scan-only and --no-scan are mutually exclusive")
                System.exit(2)
                return
            }
            val horizon = horizonMonths
            if (horizon != null && (horizon < 1 || horizon > 120)) {
                System.err.println("Error: --horizon-months must be between 1 and 120")
                System.exit(2)
                return
            }

            val productList = products
                ?.split(',')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?: emptyList()
            if (productList.size > 200) {
                System.err.println("Error: --products accepts at most 200 entries")
                System.exit(2)
                return
            }

            val effectiveUrl = getEffectiveBackendUrl()
            val authToken = cliHttpClient.authenticate(getEffectiveUsername(), getEffectivePassword(), effectiveUrl)
                ?: throw RuntimeException("Authentication failed. Check credentials.")

            println("Mode:      ${if (scanOnly) "scan only" else if (noScan) "download only" else "download + scan"}")
            if (productList.isNotEmpty()) println("Products:  ${productList.joinToString(", ")}")
            println()

            val requestBody = mapOf(
                "products" to productList,
                "scan" to !noScan,
                "scanOnly" to scanOnly,
                "horizonMonths" to horizon
            )

            val accepted = cliHttpClient.postMap("$effectiveUrl/api/eol/catalog/sync", requestBody, authToken)
                ?: throw RuntimeException("EOL sync failed - no response from server")

            val runId = accepted["runId"]?.toString()
                ?: throw RuntimeException("EOL sync failed - server returned no run id")
            println("Run id:    $runId")
            println()

            // The server answers 202 the moment the run is recorded and does the
            // work on a background thread, so a multi-minute sync no longer holds
            // an HTTP request open past the reverse proxy's read timeout (both
            // Apache and nginx default to 60s, which this run exceeds). Poll the
            // run until it reaches a terminal status.
            var result: Map<*, *> = accepted
            var waitedSeconds = 0L
            while (result["status"]?.toString() == STATUS_RUNNING) {
                if (waitedSeconds >= MAX_WAIT_SECONDS) {
                    System.err.println(
                        "Error: run $runId is still going after ${MAX_WAIT_SECONDS / 60} minutes - no longer waiting."
                    )
                    System.err.println("It continues on the server; re-check it with the run id above.")
                    System.exit(1)
                    return
                }
                Thread.sleep(POLL_INTERVAL_SECONDS * 1000)
                waitedSeconds += POLL_INTERVAL_SECONDS
                // Print progress even without -v. A run can legitimately take
                // minutes, and a command that prints nothing for that long is
                // indistinguishable from a hung one — which is exactly how a
                // slow run was reported. -v keeps the per-poll detail.
                if (verbose || waitedSeconds % PROGRESS_INTERVAL_SECONDS == 0L) {
                    println("   ... still running (${waitedSeconds}s elapsed)")
                }
                result = cliHttpClient.getMap("$effectiveUrl/api/eol/catalog/sync/$runId", authToken)
                    ?: throw RuntimeException("EOL sync failed - could not read status of run $runId")
            }
            if (waitedSeconds > 0) println()

            val status = result["status"]?.toString() ?: "UNKNOWN"
            val productsSynced = intOf(result["productsSynced"])
            val releasesSynced = intOf(result["releasesSynced"])
            val assetsScanned = intOf(result["assetsScanned"])
            val repositoriesScanned = intOf(result["repositoriesScanned"])
            val findingsWritten = intOf(result["findingsWritten"])
            val eolFindings = intOf(result["eolFindings"])
            val approaching = intOf(result["approachingFindings"])
            val findingsRemoved = intOf(result["findingsRemoved"])
            @Suppress("UNCHECKED_CAST")
            val failedProducts = (result["productsFailed"] as? List<Any?>)?.map { it.toString() } ?: emptyList()
            val errorSummary = result["errorSummary"]?.toString()

            println("Products synced:        $productsSynced")
            println("Release cycles synced:  $releasesSynced")
            println("Systems scanned:        $assetsScanned")
            println("Repositories scanned:   $repositoriesScanned")
            println("EOL findings:           $eolFindings")
            println("Approaching EOL:        $approaching")
            println("Findings written:       $findingsWritten")
            println("Stale findings removed: $findingsRemoved")
            println()

            if (verbose && failedProducts.isNotEmpty()) {
                println("Products that could not be synced:")
                failedProducts.forEach { println("   - $it") }
                println()
            }
            if (!errorSummary.isNullOrBlank()) {
                println("Note: $errorSummary")
                println()
            }

            when (status) {
                "SUCCESS" -> println("EOL catalogue sync completed successfully")
                "PARTIAL" -> println("EOL catalogue sync completed with warnings")
                else -> println("EOL catalogue sync failed")
            }

            if (status != "SUCCESS") {
                System.exit(1)
            }
        } catch (e: Exception) {
            System.err.println("Error: ${e.message}")
            if (verbose) e.printStackTrace()
            System.exit(1)
        }
    }

    private fun intOf(value: Any?): Int = (value as? Number)?.toInt() ?: 0

    private fun getEffectiveUsername(): String =
        username ?: System.getenv("SECMAN_ADMIN_NAME")
        ?: throw IllegalArgumentException("Backend username required. Use --username flag or set SECMAN_ADMIN_NAME environment variable")

    private fun getEffectivePassword(): String =
        password ?: System.getenv("SECMAN_ADMIN_PASS")
        ?: throw IllegalArgumentException("Backend password required. Use --password flag or set SECMAN_ADMIN_PASS environment variable")

    private fun getEffectiveBackendUrl(): String {
        val url = backendUrl ?: System.getenv("SECMAN_HOST") ?: System.getenv("SECMAN_BACKEND_URL") ?: "http://localhost:8080"
        return if (url.startsWith("http://") || url.startsWith("https://")) url else "https://$url"
    }

    companion object {
        /** The one non-terminal status a run can report. */
        const val STATUS_RUNNING = "RUNNING"

        private const val POLL_INTERVAL_SECONDS = 5L

        /**
         * How often the wait reports progress without `-v`. A multiple of
         * [POLL_INTERVAL_SECONDS], or the modulo below never matches.
         */
        private const val PROGRESS_INTERVAL_SECONDS = 30L

        /**
         * Matches the server's own stale-run threshold: past this point the
         * backend reclaims the run itself, so waiting longer cannot help.
         */
        private const val MAX_WAIT_SECONDS = 3600L
    }
}
