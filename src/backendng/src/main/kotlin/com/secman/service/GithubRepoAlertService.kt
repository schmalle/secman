package com.secman.service

import com.secman.domain.ExecutionStatus
import com.secman.domain.GithubRepository
import com.secman.repository.GithubRepoAlertExceptionRepository
import com.secman.repository.GithubRepoFindingSnapshotRepository
import com.secman.repository.GithubRepositoryRepository
import io.micronaut.serde.annotation.Serdeable
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * Alerts GitHub repo owners whose open high+critical Dependabot alert count
 * has NOT decreased over the last [thresholdDays] days (default 30).
 *
 * Baseline = the newest [com.secman.domain.GithubRepoFindingSnapshot] at
 * least `thresholdDays` old. A repo alerts when
 * `current(high+critical) > 0 && current >= baseline`. Repos without a
 * sufficiently old snapshot are skipped (reported, not alerted) — avoids
 * false alerts during the first month after rollout. Repos with an active
 * [com.secman.domain.GithubRepoAlertException] are skipped; repos without an
 * `ownerEmail` are reported as unmapped.
 *
 * Pure DB operation — no GitHub call. Triggered by
 * `POST /api/cli/github-repo-alerts/send` (CLI `alert-github-repo-owners`,
 * MCP `send_github_repo_alerts`).
 */
@Singleton
open class GithubRepoAlertService(
    private val githubRepositoryRepository: GithubRepositoryRepository,
    private val snapshotRepository: GithubRepoFindingSnapshotRepository,
    private val exceptionRepository: GithubRepoAlertExceptionRepository,
    private val emailService: EmailService
) {
    private val logger = LoggerFactory.getLogger(GithubRepoAlertService::class.java)

    @Serdeable
    data class AlertedRepo(
        val fullName: String,
        val htmlUrl: String?,
        val ownerEmail: String?,
        val currentCritical: Int,
        val currentHigh: Int,
        val baselineCritical: Int?,
        val baselineHigh: Int?,
        val baselineDate: Instant?
    )

    @Serdeable
    data class AlertResult(
        val status: ExecutionStatus,
        val thresholdDays: Int,
        val reposEvaluated: Int,
        val reposAlerted: Int,
        val reposExcepted: List<String>,
        val reposSkippedInsufficientHistory: List<String>,
        val unmappedRepos: List<String>,
        val emailsSent: Int,
        val emailsFailed: Int,
        val recipients: List<String>,
        val failedRecipients: List<String>,
        val alertedRepos: List<AlertedRepo>
    )

    open fun sendGithubRepoAlerts(
        dryRun: Boolean = false,
        thresholdDays: Int = 30,
        force: Boolean = false,
        onlyEmail: String? = null
    ): AlertResult {
        require(thresholdDays >= 1) { "thresholdDays must be >= 1" }
        val now = Instant.now()
        val cutoff = now.minus(thresholdDays.toLong(), ChronoUnit.DAYS)

        val repos = githubRepositoryRepository.findAll()
            .let { all -> if (onlyEmail != null) all.filter { it.ownerEmail.equals(onlyEmail, ignoreCase = true) } else all }
        val excepted = mutableListOf<String>()
        val skippedInsufficientHistory = mutableListOf<String>()
        val unmapped = mutableListOf<String>()
        val alerted = mutableListOf<AlertedRepo>()

        for (repo in repos) {
            if (hasActiveException(repo, now)) {
                excepted.add(repo.fullName)
                continue
            }
            val current = repo.criticalCount + repo.highCount
            if (current == 0) continue

            val baseline = snapshotRepository
                .findFirstByGithubRepositoryIdAndSnapshotAtLessThanEqualOrderBySnapshotAtDesc(repo.id!!, cutoff)
                .orElse(null)

            if (!force) {
                if (baseline == null) {
                    skippedInsufficientHistory.add(repo.fullName)
                    continue
                }
                if (current < baseline.criticalCount + baseline.highCount) {
                    continue // improving — no alert
                }
            }

            val detail = AlertedRepo(
                fullName = repo.fullName,
                htmlUrl = repo.htmlUrl,
                ownerEmail = repo.ownerEmail,
                currentCritical = repo.criticalCount,
                currentHigh = repo.highCount,
                baselineCritical = baseline?.criticalCount,
                baselineHigh = baseline?.highCount,
                baselineDate = baseline?.snapshotAt
            )
            if (repo.ownerEmail.isNullOrBlank()) {
                unmapped.add(repo.fullName)
            }
            alerted.add(detail)
        }

        // One email per owner listing all their non-decreasing repos.
        val byOwner = alerted
            .filter { !it.ownerEmail.isNullOrBlank() }
            .groupBy { it.ownerEmail!!.lowercase() }

        val sentRecipients = mutableListOf<String>()
        val failedRecipients = mutableListOf<String>()

        if (!dryRun) {
            for ((email, ownerRepos) in byOwner) {
                try {
                    val success = emailService.sendEmailWithInlineImages(
                        to = email,
                        subject = "SecMan: GitHub repos with non-decreasing high/critical vulnerabilities",
                        textContent = renderTextEmail(email, ownerRepos, thresholdDays),
                        htmlContent = renderHtmlEmail(email, ownerRepos, thresholdDays),
                        inlineImages = loadLogoInlineImage()
                    ).get()
                    if (success) sentRecipients.add(email) else failedRecipients.add(email)
                } catch (e: Exception) {
                    logger.warn("Failed to send GitHub repo alert to {}: {}", email, e.message)
                    failedRecipients.add(email)
                }
            }
        }

        val status = when {
            dryRun -> ExecutionStatus.DRY_RUN
            failedRecipients.isNotEmpty() && sentRecipients.isEmpty() -> ExecutionStatus.FAILURE
            failedRecipients.isNotEmpty() -> ExecutionStatus.PARTIAL_FAILURE
            else -> ExecutionStatus.SUCCESS
        }
        logger.info(
            "GitHub repo alert run ({}): {} evaluated, {} alerted, {} excepted, {} skipped, {} unmapped, {} sent, {} failed",
            status, repos.size, alerted.size, excepted.size, skippedInsufficientHistory.size,
            unmapped.size, sentRecipients.size, failedRecipients.size
        )
        return AlertResult(
            status = status,
            thresholdDays = thresholdDays,
            reposEvaluated = repos.size,
            reposAlerted = alerted.size,
            reposExcepted = excepted,
            reposSkippedInsufficientHistory = skippedInsufficientHistory,
            unmappedRepos = unmapped,
            emailsSent = sentRecipients.size,
            emailsFailed = failedRecipients.size,
            recipients = sentRecipients,
            failedRecipients = failedRecipients,
            alertedRepos = alerted
        )
    }

    private fun hasActiveException(repo: GithubRepository, now: Instant): Boolean {
        return exceptionRepository.findByGithubRepositoryId(repo.id!!).any { it.isActive(now) }
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    private val dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC)

    private fun renderHtmlEmail(email: String, repos: List<AlertedRepo>, thresholdDays: Int): String {
        val template = javaClass.getResourceAsStream("/email-templates/github-repo-alert.html")
            ?.readAllBytes()?.decodeToString()
            ?: throw IllegalStateException("github-repo-alert.html template not found")

        val rows = repos.joinToString("\n") { r ->
            val name = if (r.htmlUrl != null) {
                "<a href=\"${r.htmlUrl}\">${escapeHtml(r.fullName)}</a>"
            } else {
                escapeHtml(r.fullName)
            }
            """<tr>
                <td>$name</td>
                <td style="text-align:center;">${r.currentCritical}</td>
                <td style="text-align:center;">${r.currentHigh}</td>
                <td style="text-align:center;">${r.baselineCritical ?: "N/A"}</td>
                <td style="text-align:center;">${r.baselineHigh ?: "N/A"}</td>
                <td style="text-align:center;">${r.baselineDate?.let { dateFormat.format(it) } ?: "N/A"}</td>
            </tr>"""
        }
        return template
            .replace("\${userEmail}", escapeHtml(email))
            .replace("\${repoCount}", repos.size.toString())
            .replace("\${thresholdDays}", thresholdDays.toString())
            .replace("\${repoTableRows}", rows)
    }

    private fun renderTextEmail(email: String, repos: List<AlertedRepo>, thresholdDays: Int): String {
        val template = javaClass.getResourceAsStream("/email-templates/github-repo-alert.txt")
            ?.readAllBytes()?.decodeToString()
            ?: throw IllegalStateException("github-repo-alert.txt template not found")

        val rows = repos.joinToString("\n") { r ->
            val baselineText = if (r.baselineDate != null) {
                "(was ${r.baselineCritical} critical / ${r.baselineHigh} high on ${dateFormat.format(r.baselineDate)})"
            } else {
                "(no baseline snapshot yet - forced alert)"
            }
            "- ${r.fullName}: now ${r.currentCritical} critical / ${r.currentHigh} high $baselineText" +
                (r.htmlUrl?.let { "\n  $it" } ?: "")
        }
        return template
            .replace("\${userEmail}", email)
            .replace("\${repoCount}", repos.size.toString())
            .replace("\${thresholdDays}", thresholdDays.toString())
            .replace("\${repoTableText}", rows)
    }

    private fun loadLogoInlineImage(): Map<String, Pair<ByteArray, String>> {
        return try {
            val logoBytes = javaClass.getResourceAsStream("/email-templates/SecManLogo.png")?.readAllBytes()
            if (logoBytes != null) mapOf("secman-logo" to (logoBytes to "image/png")) else emptyMap()
        } catch (e: Exception) {
            logger.warn("Failed to load SecManLogo.png: {}", e.message)
            emptyMap()
        }
    }

    private fun escapeHtml(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}
