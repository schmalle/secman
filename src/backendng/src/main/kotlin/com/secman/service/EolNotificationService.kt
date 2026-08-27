package com.secman.service

import com.secman.domain.EolFinding
import com.secman.dto.EolNotificationRecipientResult
import com.secman.dto.EolNotificationResponse
import com.secman.repository.EolFindingRepository
import com.secman.repository.UserRepository
import io.micronaut.data.model.Pageable
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * Emails account owners about software and operating systems that reach
 * end-of-life inside the notification horizon (default: the next 12 months).
 *
 * Recipient resolution reuses [AwsAccountRecipientResolver] — the single source
 * of truth for "who owns this AWS account" already shared by the vulnerability
 * and outdated-asset mails — and falls back to `Asset.owner` resolved through
 * `UserRepository` for assets with no cloud account. No bespoke ownership model
 * is introduced here.
 *
 * Security notes:
 *  - Every resolved address is re-validated before it becomes an SMTP recipient;
 *    an address that fails is dropped, not "cleaned up" (§A07/§A03).
 *  - Component names, versions and hostnames come from imported inventory and
 *    are HTML-escaped and CR/LF-stripped before they reach the mail body or a
 *    log line (§A03 log forging, HTML injection). The body table is rendered by
 *    [EolFindingTableRenderer], the single escaping sink shared with the
 *    "Contact affected owners" broadcast, so the two mails cannot drift apart.
 *  - Dispatch is intentionally not `@Transactional`: it performs per-recipient
 *    SMTP with a multi-second timeout.
 */
@Singleton
open class EolNotificationService(
    private val eolFindingRepository: EolFindingRepository,
    private val awsAccountRecipientResolver: AwsAccountRecipientResolver,
    private val userRepository: UserRepository,
    private val emailService: EmailService,
    private val eolFindingTableRenderer: EolFindingTableRenderer
) {
    private val log = LoggerFactory.getLogger(EolNotificationService::class.java)

    /**
     * @param months horizon; components whose EOL date falls between today and
     *   today + months are reported.
     * @param includeAlreadyEol also report components already past EOL.
     * @param onlyEmail restrict delivery to this address (case-insensitive).
     */
    open fun sendEolNotifications(
        months: Long = DEFAULT_MONTHS,
        dryRun: Boolean = false,
        onlyEmail: String? = null,
        includeAlreadyEol: Boolean = false
    ): EolNotificationResponse {
        require(months in 1L..MAX_MONTHS) { "months must be between 1 and $MAX_MONTHS" }

        val today = LocalDate.now()
        val from = if (includeAlreadyEol) EARLIEST_DATE else today
        val to = today.plusMonths(months)

        val findings = loadFindings(from, to)
        val byRecipient = LinkedHashMap<String, MutableList<EolFinding>>()
        val unmappedOwners = LinkedHashSet<String>()
        val normalizedOnly = onlyEmail?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

        for (finding in findings) {
            val recipients = resolveRecipients(finding, unmappedOwners)
            for (recipient in recipients) {
                if (normalizedOnly != null && recipient != normalizedOnly) continue
                byRecipient.getOrPut(recipient) { mutableListOf() }.add(finding)
            }
        }

        var sent = 0
        var failed = 0
        val results = mutableListOf<EolNotificationRecipientResult>()

        for ((email, recipientFindings) in byRecipient) {
            val assetCount = recipientFindings.mapNotNull { it.assetId }.distinct().size
            if (dryRun) {
                results += EolNotificationRecipientResult(email, recipientFindings.size, assetCount, false, null)
                continue
            }
            val delivered = try {
                emailService.sendEmail(
                    to = email,
                    subject = buildSubject(recipientFindings, months),
                    textContent = buildTextBody(recipientFindings, months, today),
                    htmlContent = buildHtmlBody(recipientFindings, months, today)
                ).get(EMAIL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            } catch (e: Exception) {
                log.warn("EOL notification to {} failed: {}", maskEmail(email), e.message)
                false
            }
            if (delivered) sent++ else failed++
            results += EolNotificationRecipientResult(
                email = email,
                componentCount = recipientFindings.size,
                assetCount = assetCount,
                sent = delivered,
                failureReason = if (delivered) null else "Email delivery failed"
            )
        }

        // Actor is the caller (logged by the controller); target + outcome here (§A09).
        log.info(
            "EOL notification run finished: months={} dryRun={} findings={} recipients={} sent={} failed={}",
            months, dryRun, findings.size, byRecipient.size, sent, failed
        )

        return EolNotificationResponse(
            status = if (failed == 0) "SUCCESS" else "PARTIAL",
            months = months,
            dryRun = dryRun,
            findingsConsidered = findings.size,
            recipientsResolved = byRecipient.size,
            emailsSent = sent,
            emailsFailed = failed,
            unmappedOwners = unmappedOwners.take(50).toList(),
            recipients = results
        )
    }

    /** Page through the window rather than materializing the table (§A04). */
    private fun loadFindings(from: LocalDate, to: LocalDate): List<EolFinding> {
        val collected = mutableListOf<EolFinding>()
        var page = 0
        while (collected.size < MAX_FINDINGS && page < MAX_PAGES) {
            val batch = eolFindingRepository.findAssetFindingsWithEolBetween(
                from, to, Pageable.from(page, PAGE_SIZE)
            )
            if (batch.isEmpty()) break
            collected += batch
            if (batch.size < PAGE_SIZE) break
            page++
        }
        if (collected.size >= MAX_FINDINGS) {
            log.warn("EOL notification capped at {} findings; some components were not reported", MAX_FINDINGS)
        }
        return collected.take(MAX_FINDINGS)
    }

    /**
     * Account owners first (the requirement), then the asset's own owner as a
     * fallback for assets with no cloud account.
     */
    private fun resolveRecipients(finding: EolFinding, unmappedOwners: MutableSet<String>): Set<String> {
        val recipients = LinkedHashSet<String>()

        val account = finding.cloudAccountId?.trim()
        if (!account.isNullOrEmpty()) {
            awsAccountRecipientResolver.resolveAwsAccountRecipients(account)
                .mapNotNull { normalizeEmail(it) }
                .forEach { recipients += it }
        }

        val owner = finding.assetOwner?.trim()
        if (!owner.isNullOrEmpty()) {
            val ownerEmail = normalizeEmail(owner)
                ?: userRepository.findByUsername(owner).orElse(null)?.email?.let { normalizeEmail(it) }
            if (ownerEmail != null) {
                recipients += ownerEmail
            } else if (recipients.isEmpty()) {
                unmappedOwners += sanitizeForLog(owner)
            }
        }

        if (recipients.isEmpty() && (owner.isNullOrEmpty())) {
            unmappedOwners += "(asset ${finding.assetId ?: 0} has no owner)"
        }
        return recipients
    }

    /**
     * Reject rather than repair. The address becomes an SMTP recipient and is
     * echoed into a log line, so CR/LF, commas and semicolons are disqualifying,
     * not something to strip.
     */
    fun normalizeEmail(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val trimmed = raw.trim()
        if (trimmed.length > MAX_EMAIL_LENGTH) return null
        if (trimmed.any { it == '\r' || it == '\n' || it == ',' || it == ';' || it == '<' || it == '>' }) return null
        if (!EMAIL_PATTERN.matcher(trimmed).matches()) return null
        return trimmed.lowercase()
    }

    // ------------------------------------------------------------------ bodies

    private fun buildSubject(findings: List<EolFinding>, months: Long): String {
        val assets = findings.mapNotNull { it.assetId }.distinct().size
        return "Action required: $assets system(s) run software reaching end of life within $months months"
    }

    /**
     * Body rows carry the account, cloud instance, AD domain, type and status the
     * product drilldown page shows, not just the component — a recipient mapped to
     * a whole AWS account needs to know *which* system and *which* account each
     * row belongs to before they can act on it.
     */
    private fun buildTextBody(findings: List<EolFinding>, months: Long, today: LocalDate): String {
        val builder = StringBuilder()
        builder.append("The following software and operating systems on systems you own reach end of life ")
        builder.append("within the next $months months (or already have).\n\n")
        builder.append(eolFindingTableRenderer.renderText(findings, today, includeComponent = true))
        builder.append("\nPlease plan an upgrade or request an exception in secman.\n")
        return builder.toString()
    }

    private fun buildHtmlBody(findings: List<EolFinding>, months: Long, today: LocalDate): String {
        val builder = StringBuilder()
        builder.append("<p>The following software and operating systems on systems you own reach end of life ")
        builder.append("within the next ").append(months).append(" months (or already have).</p>")
        builder.append(eolFindingTableRenderer.renderHtml(findings, today, includeComponent = true))
        builder.append("<p>Please plan an upgrade or request an exception in secman.</p>")
        return builder.toString()
    }

    // ----------------------------------------------------------------- escaping

    private fun sanitizeForLog(value: String): String =
        value.replace(Regex("[\\r\\n\\t]"), "_").take(120)

    private fun maskEmail(email: String): String {
        val at = email.indexOf('@')
        if (at <= 1) return "***"
        return email.first() + "***" + email.substring(at)
    }

    companion object {
        const val DEFAULT_MONTHS = 12L
        const val MAX_MONTHS = 60L
        private const val PAGE_SIZE = 1_000
        private const val MAX_PAGES = 1_000
        private const val MAX_FINDINGS = 200_000
        private const val MAX_EMAIL_LENGTH = 254
        private const val EMAIL_TIMEOUT_SECONDS = 60L
        private val EARLIEST_DATE: LocalDate = LocalDate.of(1970, 1, 1)
        private val EMAIL_PATTERN: Pattern =
            Pattern.compile("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")
    }
}
