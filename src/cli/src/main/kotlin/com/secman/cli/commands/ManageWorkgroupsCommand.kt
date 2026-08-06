package com.secman.cli.commands

import picocli.CommandLine.*
import jakarta.inject.Singleton

/**
 * Parent command for workgroup management operations via backend HTTP API.
 *
 * Subcommands:
 *   list            - List workgroups or assets in a workgroup
 *   assign-assets   - Assign assets to a workgroup
 *   remove-assets   - Remove assets from a workgroup
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
    name = "manage-workgroups",
    description = ["Manage workgroup asset assignments"],
    mixinStandardHelpOptions = true,
    subcommands = [
        ListWorkgroupsCommand::class,
        AssignWorkgroupAssetsCommand::class,
        RemoveWorkgroupAssetsCommand::class
    ]
)
class ManageWorkgroupsCommand : Runnable {

    @Option(
        names = ["--admin-user", "-u"],
        description = ["Admin user email (defaults to SECMAN_ADMIN_EMAIL env var)"],
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

    @Spec
    lateinit var spec: Model.CommandSpec

    fun getAdminUserOrThrow(): String {
        return adminUser
            ?: System.getenv("SECMAN_ADMIN_EMAIL")
            ?: throw IllegalArgumentException(
                "Admin user required. Use --admin-user flag or set SECMAN_ADMIN_EMAIL environment variable"
            )
    }

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
        return if (url.startsWith("http://") || url.startsWith("https://")) url else "https://$url"
    }

    override fun run() {
        spec.commandLine().usage(System.out)
    }
}
