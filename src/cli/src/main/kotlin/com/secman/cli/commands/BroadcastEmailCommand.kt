package com.secman.cli.commands

import com.secman.cli.service.CliHttpClient
import jakarta.inject.Inject
import jakarta.inject.Singleton
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import java.nio.file.Files
import java.nio.file.Path

/**
 * CLI command to send a custom HTML email broadcast to every user with a recorded login.
 *
 * Wraps POST /api/admin/email-broadcast (and GET /api/admin/email-broadcast/recipients
 * for the dry-run path). Requires ADMIN role.
 */
@Singleton
@Command(
    name = "broadcast-email",
    description = ["Send a custom HTML email broadcast to every user with a recorded login (ADMIN role required)"],
    mixinStandardHelpOptions = true
)
class BroadcastEmailCommand : Runnable {

    @Inject
    lateinit var cliHttpClient: CliHttpClient

    @Option(names = ["--subject"], required = true, description = ["Email subject (1-255 characters)"])
    var subject: String = ""

    @Option(names = ["--html"], description = ["HTML body content (required unless --html-file is provided or --dry-run)"])
    var html: String? = null

    @Option(names = ["--html-file"], description = ["Path to a file containing the HTML body (alternative to --html)"])
    var htmlFile: String? = null

    @Option(names = ["--dry-run"], description = ["Report planned recipient count without creating a broadcast job"])
    var dryRun: Boolean = false

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
            val trimmedSubject = subject.trim()
            if (trimmedSubject.isBlank()) {
                throw IllegalArgumentException("--subject is required and must not be blank")
            }
            if (trimmedSubject.length > 255) {
                throw IllegalArgumentException("--subject must be 255 characters or fewer")
            }

            val effectiveUrl = getEffectiveBackendUrl()
            val effectiveUsername = getEffectiveUsername()
            val effectivePassword = getEffectivePassword()

            println("============================================================")
            println("SecMan Email Broadcast")
            println("============================================================")
            println("Subject: $trimmedSubject")
            if (dryRun) println("Mode:    dry-run") else println("Mode:    send")
            println()

            val authToken = cliHttpClient.authenticate(effectiveUsername, effectivePassword, effectiveUrl)
                ?: throw RuntimeException("Authentication failed. Check credentials.")

            if (dryRun) {
                val recipientResp = cliHttpClient.getMap(
                    "$effectiveUrl/api/admin/email-broadcast/recipients",
                    authToken
                ) ?: throw RuntimeException("Failed to fetch recipient count from server")
                val count = (recipientResp["count"] as? Number)?.toLong() ?: 0
                println("Would send to $count recipient(s) (users with lastLogin != null).")
                return
            }

            val htmlContent = resolveHtmlContent()
            if (htmlContent.isBlank()) {
                throw IllegalArgumentException("HTML body is required (use --html or --html-file)")
            }

            val (status, body) = cliHttpClient.postMapWithStatus(
                "$effectiveUrl/api/admin/email-broadcast",
                mapOf(
                    "subject" to trimmedSubject,
                    "htmlContent" to htmlContent
                ),
                authToken
            )

            if (status !in 200..299) {
                val message = body?.get("message")?.toString()
                    ?: body?.get("error")?.toString()
                    ?: "Backend returned HTTP $status"
                throw RuntimeException("Broadcast failed (HTTP $status): $message")
            }

            val jobId = (body?.get("id") as? Number)?.toLong()
            val total = (body?.get("totalRecipients") as? Number)?.toInt() ?: 0
            val jobStatus = body?.get("status")?.toString() ?: "?"

            println("Broadcast job queued:")
            println("  Job ID:         ${jobId ?: "?"}")
            println("  Status:         $jobStatus")
            println("  Recipients:     $total")
            if (jobId != null) {
                println()
                println("Poll progress with:")
                println("  GET $effectiveUrl/api/admin/email-broadcast/jobs/$jobId")
            }
        } catch (e: Exception) {
            System.err.println("Error: ${e.message}")
            if (verbose) {
                e.printStackTrace()
            }
            System.exit(1)
        }
    }

    private fun resolveHtmlContent(): String {
        if (htmlFile != null) {
            val path = Path.of(htmlFile!!)
            if (!Files.isRegularFile(path)) {
                throw IllegalArgumentException("--html-file does not exist or is not a regular file: ${htmlFile}")
            }
            return Files.readString(path).trim()
        }
        return (html ?: "").trim()
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
