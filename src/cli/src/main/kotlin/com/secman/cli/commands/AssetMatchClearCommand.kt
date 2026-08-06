package com.secman.cli.commands

import com.fasterxml.jackson.databind.ObjectMapper
import com.secman.cli.service.CliHttpClient
import com.secman.cli.service.S3DownloadException
import com.secman.cli.service.S3DownloadService
import jakarta.inject.Inject
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * `secman asset-match-clear` — reconcile AWS assets in secman against a JSON
 * snapshot of currently existing AWS resources.
 *
 * Workflow:
 *   1. Resolve bucket/key from --bucket/--key flags or
 *      AWS_ASSET_BUCKET_NAME / AWS_ASSET_BUCKET_KEY_NAME env vars.
 *   2. Download the JSON via S3DownloadService (10 MB cap, owner-only temp file).
 *   3. Parse to a flat list of {accountId, resourceId} pairs.
 *   4. With --check, GET /api/assets and report snapshot resourceIds missing
 *      from SECMan without calling the delete endpoint.
 *   5. With --check-fix, create missing SECMan assets via PUT /api/assets/import.
 *   6. Otherwise POST to /api/assets/match-clear-aws — backend deletes assets whose
 *      cloudInstanceId is NOT in the snapshot's resourceId set. By default the
 *      backend only evaluates snapshot-covered accounts; --strict evaluates all
 *      SECMan AWS assets.
 */
@Command(
    name = "asset-match-clear",
    description = ["Delete AWS assets in secman that are missing from a JSON resource snapshot stored in S3"],
    mixinStandardHelpOptions = true
)
class AssetMatchClearCommand : Runnable {

    @Inject
    lateinit var cliHttpClient: CliHttpClient

    @Inject
    lateinit var s3DownloadService: S3DownloadService

    @Inject
    lateinit var objectMapper: ObjectMapper

    @Option(
        names = ["--bucket"],
        description = ["S3 bucket name (or set AWS_ASSET_BUCKET_NAME env var)"]
    )
    var bucket: String? = null

    @Option(
        names = ["--key"],
        description = ["S3 object key for the JSON snapshot (or set AWS_ASSET_BUCKET_KEY_NAME env var)"]
    )
    var key: String? = null

    @Option(names = ["--aws-region"], description = ["AWS region (defaults to SDK chain)"])
    var awsRegion: String? = null

    @Option(names = ["--aws-profile"], description = ["AWS credential profile"])
    var awsProfile: String? = null

    @Option(names = ["--aws-access-key-id"], description = ["AWS access key ID (or AWS_ACCESS_KEY_ID)"])
    var awsAccessKeyId: String? = null

    @Option(names = ["--aws-secret-access-key"], description = ["AWS secret access key (or AWS_SECRET_ACCESS_KEY)"])
    var awsSecretAccessKey: String? = null

    @Option(names = ["--aws-session-token"], description = ["AWS session token (or AWS_SESSION_TOKEN)"])
    var awsSessionToken: String? = null

    @Option(names = ["--dry-run"], description = ["Preview matching assets without deleting them"])
    var dryRun: Boolean = false

    @Option(
        names = ["--strict"],
        description = ["Treat the snapshot as globally authoritative across all SECMan AWS assets"]
    )
    var strict: Boolean = false

    @Option(
        names = ["--check"],
        description = ["Only check whether downloaded resource IDs exist in SECMan asset inventory; perform no delete/dry-run action"]
    )
    var check: Boolean = false

    @Option(
        names = ["--check-fix"],
        description = ["Create missing SECMan assets for downloaded resource IDs; never call match-clear delete endpoint"]
    )
    var checkFix: Boolean = false

    @Option(names = ["--save"], description = ["Save the downloaded AWS JSON snapshot to /tmp/asset.json"])
    var save: Boolean = false

    @Option(
        names = ["--max-delete-percent"],
        description = ["Abort if proposed deletions exceed N% of scoped AWS assets (default: 25, set 0 to disable)"]
    )
    var maxDeletePercent: Int = 25

    @Option(names = ["--verbose", "-v"], description = ["Show matching asset details"])
    var verbose: Boolean = false

    @Option(names = ["--username"], description = ["Backend username (or SECMAN_ADMIN_NAME env var)"])
    var username: String? = null

    @Option(names = ["--password"], description = ["Backend password (or SECMAN_ADMIN_PASS env var)"])
    var password: String? = null

    @Option(names = ["--backend-url"], description = ["Backend API URL (or SECMAN_HOST / SECMAN_BACKEND_URL)"])
    var backendUrl: String? = null

    @Option(
        names = ["--insecure"],
        description = ["Disable SSL certificate verification (or SECMAN_INSECURE=true)"]
    )
    var insecure: Boolean = false

    override fun run() {
        var tempFile: Path? = null
        try {
            if (!validateOptions()) {
                System.exit(1)
            }

            val effectiveBucket = bucket
                ?: System.getenv("AWS_ASSET_BUCKET_NAME")
                ?: throw IllegalArgumentException(
                    "S3 bucket required. Use --bucket flag or set AWS_ASSET_BUCKET_NAME environment variable"
                )
            val effectiveKey = key
                ?: System.getenv("AWS_ASSET_BUCKET_KEY_NAME")
                ?: throw IllegalArgumentException(
                    "S3 object key required. Use --key flag or set AWS_ASSET_BUCKET_KEY_NAME environment variable"
                )

            val (effectiveUrl, urlSource) = getEffectiveBackendUrlWithSource()
            val (effectiveUsername, usernameSource) = getEffectiveUsernameWithSource()
            val (effectivePassword, passwordSource) = getEffectivePasswordWithSource()

            assertNotPassReference("username", effectiveUsername, usernameSource)
            assertNotPassReference("password", effectivePassword, passwordSource)
            assertNotPassReference("backend URL", effectiveUrl, urlSource)

            println("============================================================")
            println("Asset Match-Clear (AWS)")
            println("============================================================")
            println("Source     : s3://$effectiveBucket/$effectiveKey")
            println("Mode       : ${if (checkFix) "check-fix" else if (check) "check" else if (dryRun) "dry-run" else "delete"}")
            println("Scope      : ${if (strict) "strict/global" else "snapshot accounts"}")
            println("Safety brake: ${if (maxDeletePercent in 1..99) "$maxDeletePercent%" else "disabled"}")
            println()

            if (verbose) {
                printDiagnostics(
                    effectiveUrl, urlSource,
                    effectiveUsername, usernameSource,
                    effectivePassword, passwordSource
                )
            }

            val resolvedAccessKey = awsAccessKeyId ?: System.getenv("AWS_ACCESS_KEY_ID")
            val resolvedSecret = awsSecretAccessKey ?: System.getenv("AWS_SECRET_ACCESS_KEY")
            val resolvedSession = awsSessionToken ?: System.getenv("AWS_SESSION_TOKEN")
            val resolvedRegion = awsRegion ?: System.getenv("AWS_REGION")

            println("Downloading snapshot from S3...")
            tempFile = s3DownloadService.downloadToTempFile(
                bucket = effectiveBucket,
                key = effectiveKey,
                region = resolvedRegion,
                profile = awsProfile,
                accessKeyId = resolvedAccessKey,
                secretAccessKey = resolvedSecret,
                sessionToken = resolvedSession
            )
            val downloadedBytes = Files.size(tempFile)
            println("Downloaded : ${downloadedBytes / 1024} KB")

            val parsed = parseSnapshot(tempFile)
            println("Snapshot   : ${parsed.totalEntries} entries (${parsed.accountIds.size} accounts, ${parsed.resourceIds.size} resources)")
            if (parsed.skippedEntries > 0) {
                println("WARN       : ${parsed.skippedEntries} entries skipped (missing accountId or resourceId)")
            }
            println()

            if (parsed.accountIds.isEmpty() || parsed.resourceIds.isEmpty()) {
                throw RuntimeException(
                    "Snapshot has no usable (accountId, resourceId) entries — refusing to call backend."
                )
            }

            val authToken = cliHttpClient.authenticate(effectiveUsername, effectivePassword, effectiveUrl)
                ?: throw RuntimeException(
                    "Authentication failed (HTTP 401 / invalid credentials). " +
                    "Re-run with --verbose to see exactly which username, source, and backend URL were used."
                )

            if (check) {
                runInventoryCheck(effectiveUrl, authToken, parsed)
                return
            }

            if (checkFix) {
                runInventoryCheckFix(effectiveUrl, authToken, parsed)
                return
            }

            if (save) {
                val savedSnapshot = Path.of("/tmp/asset.json")
                Files.copy(tempFile, savedSnapshot, StandardCopyOption.REPLACE_EXISTING)
                println("Saved      : $savedSnapshot")
            }

            val requestBody = mapOf(
                "accountIds" to parsed.accountIds.toList(),
                "resourceIds" to parsed.resourceIds.toList(),
                "dryRun" to dryRun,
                "maxDeletePercent" to maxDeletePercent,
                "strict" to strict
            )

            val (status, result) = cliHttpClient.postMapWithStatus(
                "$effectiveUrl/api/assets/match-clear-aws",
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
            val scopedAssetCount = (result["scopedAssetCount"] as? Number)?.toInt() ?: 0
            val uncoveredAccountCount = (result["uncoveredAccountCount"] as? Number)?.toInt() ?: 0
            val uncoveredAssetCount = (result["uncoveredAssetCount"] as? Number)?.toInt() ?: 0
            val runStatus = result["status"]?.toString() ?: "UNKNOWN"
            val brakeTripped = (result["safetyBrakeTripped"] as? Boolean) ?: false

            @Suppress("UNCHECKED_CAST")
            val candidates = result["candidates"] as? List<Map<String, Any?>> ?: emptyList()
            @Suppress("UNCHECKED_CAST")
            val errors = result["errors"] as? List<Map<String, Any?>> ?: emptyList()
            @Suppress("UNCHECKED_CAST")
            val uncoveredAccounts = result["uncoveredAccounts"] as? Map<String, Any?> ?: emptyMap()

            println("Scoped AWS assets : $scopedAssetCount")
            if (!strict && uncoveredAssetCount > 0) {
                println("WARN       : $uncoveredAssetCount SECMan AWS assets in $uncoveredAccountCount accounts are outside the snapshot scope")
            }
            println("Unmatched (delete candidates): $candidateCount")
            if (dryRun) {
                println("Deleted    : 0  (dry-run)")
            } else if (brakeTripped) {
                println("Deleted    : 0  (safety brake tripped — status=$runStatus)")
            } else {
                println("Deleted    : $deletedCount")
                println("Skipped/failed: $skippedCount")
            }
            println("Status     : $runStatus")

            if (verbose && candidates.isNotEmpty()) {
                println()
                println("Matching assets:")
                candidates.forEach { candidate ->
                    val id = candidate["assetId"] ?: "?"
                    val name = candidate["name"] ?: "?"
                    val account = candidate["cloudAccountId"] ?: "?"
                    val instance = candidate["cloudInstanceId"] ?: "?"
                    println("  - $id  $name  account=$account  instance=$instance")
                }
            }

            if (verbose && !strict && uncoveredAccounts.isNotEmpty()) {
                println()
                println("Uncovered snapshot accounts:")
                uncoveredAccounts.toSortedMap().forEach { (account, count) ->
                    println("  - $account: $count assets")
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
            if (brakeTripped) {
                System.exit(2)
            }
        } catch (e: S3DownloadException) {
            System.err.println("S3 error: ${e.message}")
            if (verbose) e.printStackTrace()
            System.exit(1)
        } catch (e: Exception) {
            System.err.println("Error: ${e.message}")
            if (verbose) e.printStackTrace()
            System.exit(1)
        } finally {
            if (tempFile != null) {
                s3DownloadService.cleanupTempFile(tempFile)
            }
        }
    }

    private data class SnapshotParseResult(
        val accountIds: Set<String>,
        val resourceIds: Set<String>,
        val entries: List<SnapshotResource>,
        val totalEntries: Int,
        val skippedEntries: Int
    )

    private data class SnapshotResource(
        val accountId: String,
        val resourceId: String,
        val resourceKey: String = resourceId.lowercase()
    )

    private fun parseSnapshot(path: Path): SnapshotParseResult {
        val tree = objectMapper.readTree(path.toFile())
        if (!tree.isArray) {
            throw RuntimeException(
                "Snapshot root is not a JSON array (got ${tree.nodeType}). " +
                "Expected: [{\"accountId\":\"...\",\"resourceId\":\"...\", ...}, ...]"
            )
        }
        val accountIds = mutableSetOf<String>()
        val resourceIds = mutableSetOf<String>()
        val entries = mutableListOf<SnapshotResource>()
        var skipped = 0
        var total = 0
        for (node in tree) {
            total++
            val account = node.get("accountId")?.asText()?.trim().orEmpty()
            val resource = node.get("resourceId")?.asText()?.trim().orEmpty()
            if (account.isEmpty() || resource.isEmpty()) {
                skipped++
                continue
            }
            accountIds.add(account)
            resourceIds.add(resource.lowercase())
            entries.add(SnapshotResource(account, resource))
        }
        return SnapshotParseResult(
            accountIds = accountIds,
            resourceIds = resourceIds,
            entries = entries,
            totalEntries = total,
            skippedEntries = skipped
        )
    }

    private fun runInventoryCheck(effectiveUrl: String, authToken: String, parsed: SnapshotParseResult) {
        val assets = cliHttpClient.getList("$effectiveUrl/api/assets", authToken)
            ?: throw RuntimeException("Could not read SECMan asset inventory")
        val existingInstanceIds = assets
            .mapNotNull { it["cloudInstanceId"]?.toString()?.trim()?.lowercase()?.takeIf(String::isNotEmpty) }
            .toSet()
        val missingInstanceIds = parsed.resourceIds
            .filterNot { it in existingInstanceIds }
            .sorted()

        println("SECMan assets with instance ID : ${existingInstanceIds.size}")
        println("Downloaded instance IDs        : ${parsed.resourceIds.size}")
        println("Existing downloaded IDs        : ${parsed.resourceIds.size - missingInstanceIds.size}")
        println("Missing downloaded IDs         : ${missingInstanceIds.size}")
        println("Status     : ${if (missingInstanceIds.isEmpty()) "SUCCESS" else "MISSING"}")

        if (verbose && missingInstanceIds.isNotEmpty()) {
            println()
            println("Missing downloaded instance IDs:")
            missingInstanceIds.forEach { id ->
                println("  - $id")
            }
        }
    }

    private data class CheckFixCandidate(
        val accountId: String,
        val resourceId: String,
        val resourceKey: String
    )

    private fun runInventoryCheckFix(effectiveUrl: String, authToken: String, parsed: SnapshotParseResult) {
        val assets = cliHttpClient.getList("$effectiveUrl/api/assets", authToken)
            ?: throw RuntimeException("Could not read SECMan asset inventory")
        val existingInstanceIds = assets
            .mapNotNull { it["cloudInstanceId"]?.toString()?.trim()?.lowercase()?.takeIf(String::isNotEmpty) }
            .toSet()

        val byResourceKey = parsed.entries.groupBy { it.resourceKey }
        val missing = byResourceKey
            .filterKeys { it !in existingInstanceIds }
            .values
            .mapNotNull { entries ->
                val accountIds = entries.map { it.accountId }.toSet()
                if (accountIds.size == 1) {
                    val first = entries.first()
                    CheckFixCandidate(first.accountId, first.resourceId, first.resourceKey)
                } else {
                    null
                }
            }
            .sortedBy { it.resourceKey }
        val ambiguous = byResourceKey
            .filterKeys { it !in existingInstanceIds }
            .values
            .filter { entries -> entries.map { it.accountId }.toSet().size > 1 }
            .map { entries ->
                val first = entries.first()
                CheckFixCandidate(
                    accountId = entries.map { it.accountId }.distinct().sorted().joinToString(","),
                    resourceId = first.resourceId,
                    resourceKey = first.resourceKey
                )
            }
            .sortedBy { it.resourceKey }

        val created = mutableListOf<CheckFixCandidate>()
        val failed = mutableListOf<Pair<CheckFixCandidate, String>>()

        missing.forEach { candidate ->
            val requestBody = mapOf(
                "name" to candidate.resourceId,
                "type" to "SERVER",
                "owner" to "AWS Asset Inventory",
                "description" to "Auto-created by asset-match-clear --check-fix from AWS snapshot",
                "cloudAccountId" to candidate.accountId,
                "cloudInstanceId" to candidate.resourceId
            )
            val (status, result) = cliHttpClient.putMapWithStatus(
                "$effectiveUrl/api/assets/import",
                requestBody,
                authToken
            )
            if (status in 200..299 && result != null) {
                created.add(candidate)
            } else {
                val error = result?.get("error")?.toString() ?: "Backend returned HTTP $status"
                failed.add(candidate to error)
            }
        }

        val missingCount = missing.size + ambiguous.size
        val failedOrSkippedCount = failed.size + ambiguous.size

        println("SECMan assets with instance ID : ${existingInstanceIds.size}")
        println("Downloaded instance IDs        : ${parsed.resourceIds.size}")
        println("Existing downloaded IDs        : ${parsed.resourceIds.size - missingCount}")
        println("Missing downloaded IDs         : $missingCount")
        println("Created assets                 : ${created.size}")
        println("Failed/skipped creates         : $failedOrSkippedCount")
        println("Status     : ${if (missingCount == created.size) "SUCCESS" else "PARTIAL"}")

        if (verbose && created.isNotEmpty()) {
            println()
            println("Created assets:")
            created.forEach { candidate ->
                println("  - ${candidate.resourceId} account=${candidate.accountId}")
            }
        }

        if (verbose && (ambiguous.isNotEmpty() || failed.isNotEmpty())) {
            println()
            println("Failed/skipped creates:")
            ambiguous.forEach { candidate ->
                println("  - ${candidate.resourceId} account=${candidate.accountId}: duplicate resourceId across accounts")
            }
            failed.forEach { (candidate, error) ->
                println("  - ${candidate.resourceId} account=${candidate.accountId}: $error")
            }
        }
    }

    fun validateOptions(): Boolean {
        if (checkFix && check) {
            System.err.println("Error: --check-fix cannot be combined with --check")
            return false
        }
        if (checkFix && dryRun) {
            System.err.println("Error: --check-fix cannot be combined with --dry-run")
            return false
        }
        return true
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

    private fun mask(value: String): String {
        if (value.isEmpty()) return "(empty)"
        if (value.length <= 4) return "*".repeat(value.length) + " (length=${value.length})"
        val head = value.take(2)
        val tail = value.takeLast(2)
        return "$head${"*".repeat((value.length - 4).coerceAtMost(8))}$tail (length=${value.length})"
    }

    private fun maskUsername(value: String): String {
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
        if (user != user.trim()) println("WARN  username has leading/trailing whitespace")
        if (pass != pass.trim()) println("WARN  password has leading/trailing whitespace")
        println("--------------------------------------------------------------")
        println()
    }

    private fun assertNotPassReference(field: String, value: String, source: String) {
        if (value.startsWith("pass://")) {
            throw IllegalArgumentException(
                "$field looks like an unresolved pass-cli reference ('${value.take(40)}…') from $source.\n" +
                "  Cause: the parent shell expanded \$VAR into argv before 'pass-cli run --' resolved it.\n" +
                "  Fix:   drop the redundant CLI flag and let the binary read the env var, or use \$(pass-cli get …)."
            )
        }
    }
}
