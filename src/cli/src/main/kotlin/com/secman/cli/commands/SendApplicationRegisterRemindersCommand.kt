package com.secman.cli.commands

import com.secman.cli.service.CliHttpClient
import jakarta.inject.Inject
import jakarta.inject.Singleton
import picocli.CommandLine.Command
import picocli.CommandLine.Option

@Singleton
@Command(
    name = "send-application-register-reminders",
    description = ["Send reminders for application register entries not checked recently"],
    mixinStandardHelpOptions = true
)
class SendApplicationRegisterRemindersCommand : Runnable {
    @Option(names = ["--days"], description = ["Threshold in days (default: 365)"])
    var days: Int = 365

    @Option(names = ["--dry-run"], description = ["Preview reminders without sending emails"])
    var dryRun: Boolean = false

    @Option(names = ["--verbose", "-v"], description = ["Detailed output"])
    var verbose: Boolean = false

    @Option(names = ["--username"])
    var username: String? = null

    @Option(names = ["--password"])
    var password: String? = null

    @Option(names = ["--backend-url"])
    var backendUrl: String? = null

    @Inject lateinit var cliHttpClient: CliHttpClient

    override fun run() {
        require(days > 0) { "--days must be > 0" }
        val url = backendUrl ?: System.getenv("SECMAN_HOST") ?: System.getenv("SECMAN_BACKEND_URL") ?: "http://localhost:8080"
        val effectiveUrl = if (url.startsWith("http://") || url.startsWith("https://")) url else "https://$url"
        val user = username ?: System.getenv("SECMAN_ADMIN_NAME") ?: error("Missing username")
        val pass = password ?: System.getenv("SECMAN_ADMIN_PASS") ?: error("Missing password")
        val token = cliHttpClient.authenticate(user, pass, effectiveUrl) ?: error("Authentication failed")
        val result = cliHttpClient.postMap(
            "$effectiveUrl/api/cli/application-register/reminders/send",
            mapOf("thresholdDays" to days, "dryRun" to dryRun, "verbose" to verbose),
            token
        ) ?: error("No response")

        println("Status: ${result["status"]}")
        println("Threshold days: ${result["thresholdDays"]}")
        println("Entries overdue: ${result["entriesOverdue"]}")
        println("Recipients: ${result["recipientCount"]}")
        println("Emails sent: ${result["emailsSent"]}")
        println("Emails failed: ${result["emailsFailed"]}")
    }
}
