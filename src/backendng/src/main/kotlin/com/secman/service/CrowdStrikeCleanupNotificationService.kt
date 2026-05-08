package com.secman.service

import com.secman.domain.CrowdStrikeCleanupRun
import com.secman.domain.CrowdStrikeCleanupStatus
import com.secman.domain.User
import com.secman.repository.UserRepository
import io.micronaut.scheduling.annotation.Async
import jakarta.inject.Inject
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.time.format.DateTimeFormatter
import java.util.concurrent.CompletableFuture

@Singleton
open class CrowdStrikeCleanupNotificationService(
    @Inject private val userRepository: UserRepository,
    @Inject private val emailService: EmailService
) {
    private val logger = LoggerFactory.getLogger(CrowdStrikeCleanupNotificationService::class.java)

    @Async
    open fun notifyAdmins(run: CrowdStrikeCleanupRun): CompletableFuture<Void> {
        return CompletableFuture.runAsync {
            try {
                val admins = userRepository.findAll().filter { it.hasRole(User.Role.ADMIN) }
                if (admins.isEmpty()) {
                    logger.warn("No ADMIN users to notify of CrowdStrike cleanup run {}", run.id)
                    return@runAsync
                }

                val subject = buildSubject(run)
                val html = buildHtml(run)

                admins.forEach { admin ->
                    val email = admin.email
                    if (email.isNullOrBlank() || !isValidEmail(email)) {
                        logger.warn("Skipping admin {} (invalid/missing email)", admin.username)
                        return@forEach
                    }
                    emailService.sendHtmlEmail(email, subject, html)
                        .whenComplete { ok, err ->
                            if (err != null) {
                                logger.error("Failed to send cleanup notification to {}: {}", email, err.message, err)
                            } else if (ok != true) {
                                logger.warn("Cleanup notification email returned false for {}", email)
                            }
                        }
                }
            } catch (e: Exception) {
                logger.error("Failed to dispatch CrowdStrike cleanup notifications", e)
            }
        }
    }

    private fun buildSubject(run: CrowdStrikeCleanupRun): String {
        val tag = when (run.status) {
            CrowdStrikeCleanupStatus.SUCCESS -> "completed"
            CrowdStrikeCleanupStatus.PARTIAL -> "completed with errors"
            CrowdStrikeCleanupStatus.ABORTED_SAFETY_BRAKE -> "aborted (safety brake)"
            CrowdStrikeCleanupStatus.FAILED -> "FAILED"
        }
        return "[secman] CrowdStrike stale-asset cleanup $tag — ${run.deletedCount} deleted"
    }

    private fun buildHtml(run: CrowdStrikeCleanupRun): String {
        val cutoff = run.cutoff.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        val started = run.startedAt.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        val completed = run.completedAt?.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) ?: "—"
        val errorBlock = run.errorMessage?.let {
            """<p><b>Error:</b> ${escape(it)}</p>"""
        } ?: ""
        return """
            <html><body style="font-family: sans-serif">
              <h2>CrowdStrike stale-asset cleanup ${escape(run.status.name)}</h2>
              <table cellpadding="4" style="border-collapse: collapse">
                <tr><td><b>Triggered by</b></td><td>${escape(run.triggeredBy)}</td></tr>
                <tr><td><b>Stale threshold</b></td><td>${run.staleDays} days</td></tr>
                <tr><td><b>Cutoff</b></td><td>$cutoff</td></tr>
                <tr><td><b>CrowdStrike-tracked assets (total)</b></td><td>${run.totalCrowdStrikeTracked}</td></tr>
                <tr><td><b>Stale candidates</b></td><td>${run.candidateCount}</td></tr>
                <tr><td><b>Deleted</b></td><td>${run.deletedCount}</td></tr>
                <tr><td><b>Errors</b></td><td>${run.errorCount}</td></tr>
                <tr><td><b>Started</b></td><td>$started</td></tr>
                <tr><td><b>Completed</b></td><td>$completed</td></tr>
                <tr><td><b>Duration</b></td><td>${run.durationMs ?: "—"} ms</td></tr>
              </table>
              $errorBlock
            </body></html>
        """.trimIndent()
    }

    private fun escape(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun isValidEmail(email: String): Boolean =
        email.contains("@") && email.contains(".")
}
