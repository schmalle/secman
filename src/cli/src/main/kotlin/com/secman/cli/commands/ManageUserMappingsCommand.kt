package com.secman.cli.commands

import picocli.CommandLine.*
import jakarta.inject.Singleton

/**
 * Parent command for user mapping management operations (Feature 049)
 *
 * Provides subcommands for creating, listing, and removing user-to-domain
 * and user-to-AWS-account mappings via CLI.
 *
 * Usage:
 *   ./gradlew cli:run --args='manage-user-mappings <subcommand> [options]'
 *
 * Subcommands:
 *   add-domain  - Add domain-to-user mappings
 *   add-aws     - Add AWS-account-to-user mappings
 *   list        - List existing mappings; supports `--type AWS|DOMAIN|ALL` and
 *                 `--output <file>` to download mappings (CSV round-trips
 *                 through `import`)
 *   remove      - Remove user mappings
 *   import      - Batch import from CSV/JSON file
 *   import-s3   - Batch import from AWS S3 bucket (Feature 065)
 *   list-bucket - List objects in an S3 bucket (Feature 065)
 *   download-s3 - Download an AWS account mapping file directly from S3 to a
 *                 local path (no backend involvement)
 *   print-s3    - Download a mapping file from S3 and print the parsed
 *                 mappings to the console (no disk write, no backend)
 *   download-parse - Download an AWS account mapping file from S3 and print,
 *                 per user, which AWS accounts are mapped and how many
 *                 (no disk write, no backend)
 *
 * Authentication:
 *   All operations require ADMIN role and backend credentials.
 *   Specify via CLI flags or environment variables:
 *   - --username / SECMAN_ADMIN_NAME
 *   - --password / SECMAN_ADMIN_PASS
 *   - --backend-url / SECMAN_HOST / SECMAN_BACKEND_URL
 */
@Singleton
@Command(
    name = "manage-user-mappings",
    description = [
        "Manage user mappings for domains and AWS accounts. " +
            "The 'list' subcommand supports --send-email to distribute a " +
            "statistics report (aggregates + per-user detail) to all ADMIN/REPORT " +
            "users in a single invocation, and --type AWS|DOMAIN|ALL combined " +
            "with --output <file> to download a scoped mapping file (the CSV " +
            "form is round-trip compatible with the 'import' subcommand). " +
            "The 'download-s3' subcommand fetches an AWS account mapping file " +
            "directly from an S3 bucket to a local path without touching the " +
            "secman backend. The 'print-s3' subcommand downloads a mapping " +
            "file from S3 and prints the parsed mappings to the console " +
            "(no disk write, no backend; defaults to AWS account mappings)."
    ],
    mixinStandardHelpOptions = true,
    subcommands = [
        AddDomainCommand::class,
        AddAwsCommand::class,
        ListCommand::class,
        RemoveCommand::class,
        ImportCommand::class,
        ImportS3Command::class,
        ListBucketCommand::class,
        DownloadS3Command::class,
        PrintS3Command::class,
        DownloadParseCommand::class
    ]
)
class ManageUserMappingsCommand : Runnable {

    @Option(
        names = ["--admin-user", "-u"],
        description = ["(Deprecated) Admin user email - identity is now derived from backend credentials"],
        scope = ScopeType.INHERIT
    )
    var adminUser: String? = null

    @Option(
        names = ["--username"],
        description = ["Backend username (or set SECMAN_ADMIN_NAME env var)"],
        scope = ScopeType.INHERIT
    )
    var username: String? = null

    @Option(
        names = ["--password"],
        description = ["Backend password (or set SECMAN_ADMIN_PASS env var)"],
        scope = ScopeType.INHERIT
    )
    var password: String? = null

    @Option(
        names = ["--backend-url"],
        description = ["Backend API URL (or set SECMAN_HOST / SECMAN_BACKEND_URL env var)"],
        scope = ScopeType.INHERIT
    )
    var backendUrl: String? = null

    @Option(
        names = ["--insecure"],
        description = ["Disable SSL certificate verification (for self-signed certificates). Can also set SECMAN_INSECURE=true"],
        scope = ScopeType.INHERIT
    )
    var insecure: Boolean = false

    /**
     * Check if insecure mode is enabled via CLI flag or SECMAN_INSECURE env var.
     */
    fun isEffectiveInsecure(): Boolean {
        if (insecure) return true
        val envValue = System.getenv("SECMAN_INSECURE")?.lowercase()?.trim()
        return envValue in listOf("true", "1", "yes")
    }

    @Spec
    lateinit var spec: Model.CommandSpec

    fun getEffectiveUsername(): String {
        return username
            ?: System.getenv("SECMAN_ADMIN_NAME")
            ?: throw IllegalArgumentException(
                "Backend username required. Use --username flag or set SECMAN_ADMIN_NAME environment variable"
            )
    }

    fun getEffectivePassword(): String {
        return password
            ?: System.getenv("SECMAN_ADMIN_PASS")
            ?: throw IllegalArgumentException(
                "Backend password required. Use --password flag or set SECMAN_ADMIN_PASS environment variable"
            )
    }

    fun getEffectiveBackendUrl(): String {
        val url = backendUrl
            ?: System.getenv("SECMAN_HOST")
            ?: System.getenv("SECMAN_BACKEND_URL")
            ?: "http://localhost:8080"
        // Ensure URL has a scheme — default to https:// for scheme-less URLs
        // (scheme-less URLs like "server:8443" cause NPE in DefaultHttpClient.isSecureScheme)
        val withScheme = if (url.startsWith("http://") || url.startsWith("https://")) url
                         else "https://$url"
        return withScheme.trimEnd('/')
    }

    override fun run() {
        // Show help if no subcommand specified
        spec.commandLine().usage(System.out)
    }
}
