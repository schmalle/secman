package com.secman.cli.commands

import picocli.CommandLine.*
import jakarta.inject.Singleton

/**
 * Parent command for GitHub owner email mapping management.
 *
 * Maps a GitHub owner (org/user login) to a default notification email;
 * repositories under that owner with a blank ownerEmail are backfilled
 * immediately when a mapping is created/updated, and auto-filled on future
 * imports.
 *
 * Usage:
 *   ./scripts/secman manage-github-owner-mappings add --owner acme-corp --email owner@example.com
 *   ./scripts/secman manage-github-owner-mappings list
 *   ./scripts/secman manage-github-owner-mappings remove --owner acme-corp
 *   ./scripts/secman manage-github-owner-mappings import --file mappings.csv
 *   ./scripts/secman manage-github-owner-mappings discover --dry-run
 *
 * Authentication:
 *   Requires ADMIN or VULN role and backend credentials via CLI flags or
 *   environment variables:
 *   - --username / SECMAN_ADMIN_NAME
 *   - --password / SECMAN_ADMIN_PASS
 *   - --backend-url / SECMAN_HOST / SECMAN_BACKEND_URL
 */
@Singleton
@Command(
    name = "manage-github-owner-mappings",
    description = ["Manage GitHub owner (org/user login) to default email mappings"],
    mixinStandardHelpOptions = true,
    subcommands = [
        AddGithubOwnerMappingCommand::class,
        ListGithubOwnerMappingsCommand::class,
        RemoveGithubOwnerMappingCommand::class,
        ImportGithubOwnerMappingsCommand::class,
        DiscoverGithubOwnerMappingsCommand::class
    ]
)
class ManageGithubOwnerMappingsCommand : Runnable {

    @Option(names = ["--username"], description = ["Backend username (or set SECMAN_ADMIN_NAME env var)"], scope = ScopeType.INHERIT)
    var username: String? = null

    @Option(names = ["--password"], description = ["Backend password (or set SECMAN_ADMIN_PASS env var)"], scope = ScopeType.INHERIT)
    var password: String? = null

    @Option(names = ["--backend-url"], description = ["Backend API URL (or set SECMAN_HOST / SECMAN_BACKEND_URL env var)"], scope = ScopeType.INHERIT)
    var backendUrl: String? = null

    @Spec
    lateinit var spec: Model.CommandSpec

    fun getEffectiveUsername(): String {
        return username ?: System.getenv("SECMAN_ADMIN_NAME")
            ?: throw IllegalArgumentException("Backend username required. Use --username flag or set SECMAN_ADMIN_NAME environment variable")
    }

    fun getEffectivePassword(): String {
        return password ?: System.getenv("SECMAN_ADMIN_PASS")
            ?: throw IllegalArgumentException("Backend password required. Use --password flag or set SECMAN_ADMIN_PASS environment variable")
    }

    fun getEffectiveBackendUrl(): String {
        val url = backendUrl ?: System.getenv("SECMAN_HOST") ?: System.getenv("SECMAN_BACKEND_URL") ?: "http://localhost:8080"
        val withScheme = if (url.startsWith("http://") || url.startsWith("https://")) url else "https://$url"
        return withScheme.trimEnd('/')
    }

    override fun run() {
        spec.commandLine().usage(System.out)
    }
}
