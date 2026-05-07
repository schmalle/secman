package com.secman.cli.commands

import com.secman.cli.service.CliHttpClient
import jakarta.inject.Inject
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters

@Command(
    name = "delete-asset-not-seen",
    description = ["Delete CrowdStrike-imported assets not seen in CrowdStrike for more than N days"],
    mixinStandardHelpOptions = true
)
class DeleteAssetNotSeenCommand : Runnable {

    @Inject
    lateinit var cliHttpClient: CliHttpClient

    @Parameters(index = "0", description = ["Days since last CrowdStrike import"])
    var days: Int = 0

    @Option(names = ["--dry-run"], description = ["Preview matching assets without deleting them"])
    var dryRun: Boolean = false

    @Option(names = ["--verbose", "-v"], description = ["Show matching asset details"])
    var verbose: Boolean = false

    @Option(names = ["--username"], description = ["Backend username (or set SECMAN_ADMIN_NAME env var)"])
    var username: String? = null

    @Option(names = ["--password"], description = ["Backend password (or set SECMAN_ADMIN_PASS env var)"])
    var password: String? = null

    @Option(names = ["--backend-url"], description = ["Backend API URL (or set SECMAN_BACKEND_URL env var)"])
    var backendUrl: String? = null

    @Option(
        names = ["--insecure"],
        description = ["Disable SSL certificate verification (accept self-signed certs). Can also set SECMAN_INSECURE=true"]
    )
    var insecure: Boolean = false

    override fun run() {
        try {
            if (days <= 0) {
                throw IllegalArgumentException("Days must be greater than zero")
            }

            val (effectiveUrl, urlSource) = getEffectiveBackendUrlWithSource()
            val (effectiveUsername, usernameSource) = getEffectiveUsernameWithSource()
            val (effectivePassword, passwordSource) = getEffectivePasswordWithSource()

            println("============================================================")
            println("Delete CrowdStrike Assets Not Seen")
            println("============================================================")
            println("Threshold: $days days")
            println("Mode: ${if (dryRun) "dry-run" else "delete"}")
            println()

            if (verbose) {
                printDiagnostics(
                    effectiveUrl, urlSource,
                    effectiveUsername, usernameSource,
                    effectivePassword, passwordSource
                )
            }

            assertNotPassReference("username", effectiveUsername, usernameSource)
            assertNotPassReference("password", effectivePassword, passwordSource)
            assertNotPassReference("backend URL", effectiveUrl, urlSource)

            val authToken = cliHttpClient.authenticate(effectiveUsername, effectivePassword, effectiveUrl)
                ?: throw RuntimeException(
                    "Authentication failed (HTTP 401 / invalid credentials). " +
                    "Re-run with --verbose to see exactly which username, source, and backend URL were used."
                )

            val requestBody = mapOf(
                "days" to days,
                "dryRun" to dryRun
            )

            val (status, result) = cliHttpClient.postMapWithStatus(
                "$effectiveUrl/api/assets/delete-not-seen-by-crowdstrike",
                requestBody,
                authToken
            )

            if (status !in 200..299 || result == null) {
                val error = result?.get("error")?.toString() ?: "Backend returned HTTP $status"
                throw RuntimeException(error)
            }

            val candidateCount = (result["candidateCount"] as? Number)?.toInt() ?: 0
            val deletedCount = (result["deletedCount"] as? Number)?.toInt() ?: 0
            val skippedCount = (result["skippedCount"] as? Number)?.toInt() ?: 0
            val cutoff = result["cutoff"]?.toString() ?: ""

            @Suppress("UNCHECKED_CAST")
            val candidates = result["candidates"] as? List<Map<String, Any?>> ?: emptyList()
            @Suppress("UNCHECKED_CAST")
            val errors = result["errors"] as? List<Map<String, Any?>> ?: emptyList()

            println("Cutoff: $cutoff")
            println("Candidates: $candidateCount")

            if (dryRun) {
                println("Deleted: 0")
                println("Dry run complete - no assets were deleted")
            } else {
                println("Deleted: $deletedCount")
                println("Skipped/failed: $skippedCount")
            }

            if (verbose && candidates.isNotEmpty()) {
                println()
                println("Matching assets:")
                candidates.forEach { candidate ->
                    val id = candidate["assetId"] ?: "?"
                    val name = candidate["name"] ?: "?"
                    val importedAt = candidate["crowdStrikeLastImportedAt"] ?: "?"
                    println("  - $id  $name  last imported: $importedAt")
                }
            }

            if (errors.isNotEmpty()) {
                println()
                println("Deletion failures:")
                errors.forEach { error ->
                    val id = error["assetId"] ?: "?"
                    val name = error["assetName"] ?: "?"
                    val message = error["message"] ?: "unknown error"
                    println("  - $id  $name: $message")
                }
                System.exit(1)
            }
        } catch (e: Exception) {
            System.err.println("Error: ${e.message}")
            if (verbose) {
                e.printStackTrace()
            }
            System.exit(1)
        }
    }

    private fun getEffectiveUsernameWithSource(): Pair<String, String> {
        username?.let { return it to "--username flag" }
        System.getenv("SECMAN_ADMIN_NAME")?.let { return it to "SECMAN_ADMIN_NAME env var" }
        throw IllegalArgumentException(
            "Backend username required. Use --username flag or set SECMAN_ADMIN_NAME environment variable"
        )
    }

    private fun getEffectivePasswordWithSource(): Pair<String, String> {
        password?.let { return it to "--password flag" }
        System.getenv("SECMAN_ADMIN_PASS")?.let { return it to "SECMAN_ADMIN_PASS env var" }
        throw IllegalArgumentException(
            "Backend password required. Use --password flag or set SECMAN_ADMIN_PASS environment variable"
        )
    }

    private fun getEffectiveBackendUrlWithSource(): Pair<String, String> {
        val (raw, source) = when {
            backendUrl != null -> backendUrl!! to "--backend-url flag"
            System.getenv("SECMAN_HOST") != null -> System.getenv("SECMAN_HOST")!! to "SECMAN_HOST env var"
            System.getenv("SECMAN_BACKEND_URL") != null -> System.getenv("SECMAN_BACKEND_URL")!! to "SECMAN_BACKEND_URL env var"
            else -> "http://localhost:8080" to "default"
        }
        val normalized = if (raw.startsWith("http://") || raw.startsWith("https://")) raw else "https://$raw"
        return normalized to source
    }

    /**
     * Mask a credential while preserving enough characters to spot misconfiguration
     * (e.g. a `pass://...` reference, leading/trailing whitespace, wrong case).
     */
    private fun mask(value: String): String {
        if (value.isEmpty()) return "(empty)"
        if (value.length <= 4) return "*".repeat(value.length) + " (length=${value.length})"
        val head = value.take(2)
        val tail = value.takeLast(2)
        return "$head${"*".repeat((value.length - 4).coerceAtMost(8))}$tail (length=${value.length})"
    }

    private fun maskUsername(value: String): String {
        // Usernames aren't as sensitive as passwords; show more characters to aid debugging.
        if (value.length <= 4) return value
        return value.take(3) + "…" + value.takeLast(2) + " (length=${value.length})"
    }

    private fun printDiagnostics(
        url: String, urlSource: String,
        user: String, userSource: String,
        pass: String, passSource: String
    ) {
        println("--- Verbose diagnostics --------------------------------------")
        println("Backend URL : $url   [from: $urlSource]")
        println("Username    : ${maskUsername(user)}   [from: $userSource]")
        println("Password    : ${mask(pass)}   [from: $passSource]")
        val insecureProp = System.getProperty("secman.ssl.insecure")
        val insecureEnv = System.getenv("SECMAN_INSECURE")
        println("Insecure SSL: --insecure=$insecure, secman.ssl.insecure=$insecureProp, SECMAN_INSECURE=$insecureEnv")
        // Trailing whitespace is invisible in shells but would break auth — flag it explicitly.
        if (user != user.trim()) println("WARN  username has leading/trailing whitespace")
        if (pass != pass.trim()) println("WARN  password has leading/trailing whitespace")
        println("--------------------------------------------------------------")
        println()
    }

    /**
     * Detect the common shell-expansion-vs-pass-cli ordering trap: when a wrapper
     * script does `--username $SECMAN_ADMIN_NAME` with `SECMAN_ADMIN_NAME=pass://...`,
     * the shell expands the literal `pass://...` into argv before `pass-cli run --`
     * resolves env vars, so the backend receives the unresolved reference and
     * returns 401. Fail fast with a clear message instead of leaving the user to
     * decode an opaque "invalid credentials" error.
     */
    private fun assertNotPassReference(field: String, value: String, source: String) {
        if (value.startsWith("pass://")) {
            throw IllegalArgumentException(
                "$field looks like an unresolved pass-cli reference ('${value.take(40)}…') " +
                "from $source.\n" +
                "  Cause: the parent shell expanded \$VAR into argv before 'pass-cli run --' " +
                "resolved it. pass-cli rewrites env vars at exec time, not argv that the shell " +
                "already substituted.\n" +
                "  Fix:   either drop the redundant CLI flag and let the binary read the env " +
                "var (the CLI already supports this), or use \$(pass-cli get …) command " +
                "substitution instead of \$VAR."
            )
        }
    }
}
