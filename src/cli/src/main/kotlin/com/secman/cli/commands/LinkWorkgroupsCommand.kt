package com.secman.cli.commands

import com.secman.cli.service.UserMappingCliService
import jakarta.inject.Singleton
import picocli.CommandLine.*

/**
 * CLI command for the workgroup-linking correction path.
 *
 * Links every AWS account whose user mapping carries a display name to the workgroup
 * named "aws-<display name>", creating that workgroup when it does not exist. The
 * source is what the database already holds, so no file is needed — which is the point:
 * this repairs mappings imported before display names were captured, or while the
 * matching workgroup was temporarily missing.
 *
 * The same work happens automatically during `manage-user-mappings import` for every
 * account the file names. This command exists for everything the last import did not
 * cover.
 *
 * Usage:
 *   secman manage-user-mappings link-workgroups
 *   secman manage-user-mappings link-workgroups --dry-run
 *
 * Idempotent: an account already assigned to its workgroup is reported as
 * "already linked" and is not an error. Existing assignments are never removed —
 * an account renamed between imports keeps its old workgroup, and the report shows
 * the new link beside it.
 *
 * Requires ADMIN role.
 */
@Singleton
@Command(
    name = "link-workgroups",
    description = [
        "Link AWS accounts to the workgroup named after their display name " +
            "(aws-<display name>), creating missing workgroups. Corrects mappings " +
            "imported before display names were captured."
    ],
    mixinStandardHelpOptions = true,
    footer = [
        "",
        "Examples:",
        "  # See what would be linked, change nothing",
        "  secman manage-user-mappings link-workgroups --dry-run",
        "",
        "  # Link for real",
        "  secman manage-user-mappings link-workgroups",
        "",
        "Exit codes: 0 success (accounts already linked are NOT failures) -",
        "1 one or more accounts could not be linked.",
        ""
    ]
)
/**
 * `manage-user-mappings link-workgroups` — links AWS accounts to workgroups from the
 * display names already stored on user mappings, so no file has to be supplied.
 *
 * Exit status comes from [WorkgroupLinkPrinter.print], which counts only real
 * failures: an already-linked account is an idempotent no-op. That is what makes
 * re-running the documented way to finish a run the backend reported as truncated.
 */
class LinkWorkgroupsCommand(
    private val userMappingCliService: UserMappingCliService
) : Runnable {

    @ParentCommand
    lateinit var parent: ManageUserMappingsCommand

    @Option(
        names = ["--dry-run"],
        description = ["Report what would be linked without creating workgroups or assignments"]
    )
    var dryRun: Boolean = false

    override fun run() {
        try {
            println("=".repeat(60))
            println("Link AWS Accounts to Workgroups")
            println("=".repeat(60))
            println()

            val backendUrl = parent.getEffectiveBackendUrl()
            val username = parent.getEffectiveUsername()
            val password = parent.getEffectivePassword()
            userMappingCliService.initHttpClient(backendUrl, parent.isEffectiveInsecure())
            val token = userMappingCliService.authenticate(username, password, backendUrl)
                ?: throw IllegalArgumentException("Authentication failed - check username/password")

            println("Backend: $backendUrl")
            println("Source:  AWS account display names stored on existing user mappings")
            if (dryRun) {
                println("Mode:    DRY-RUN (nothing will be created or assigned)")
            }

            val summary = userMappingCliService.linkWorkgroupAccounts(
                dryRun = dryRun,
                backendUrl = backendUrl,
                authToken = token
            )

            val failures = WorkgroupLinkPrinter.print(summary)
            println()

            if (summary.processed == 0) {
                println("No AWS account display names are stored yet — nothing to link.")
                println("Run an import of a mapping file that carries display_name first.")
            }

            if (failures > 0) {
                println("✗ Completed with $failures failure(s)")
                System.exit(1)
            } else if (dryRun) {
                println("✓ Dry run complete")
            } else {
                println("✓ Linking complete")
            }
        } catch (e: IllegalArgumentException) {
            println()
            System.err.println("❌ Error: ${e.message}")
            System.exit(1)
        } catch (e: Exception) {
            println()
            System.err.println("❌ Error: ${e.message}")
            System.exit(1)
        }
    }
}
