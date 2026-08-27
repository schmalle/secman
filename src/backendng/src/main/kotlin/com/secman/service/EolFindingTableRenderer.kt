package com.secman.service

import com.secman.domain.EolFinding
import com.secman.domain.EolStatus
import com.secman.domain.EolSubjectType
import jakarta.inject.Singleton
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * Renders a set of [EolFinding] rows as the affected-systems table that both EOL
 * mail paths embed — the scheduled/CLI owner notification
 * ([EolNotificationService]) and the "Contact affected owners" broadcast from
 * the product drilldown page ([EmailBroadcastService]).
 *
 * It exists so those two mails cannot drift apart: the columns here are exactly
 * the ones `EolProductAssetsPage.tsx` and its CSV export show, so an operator
 * reading the mail and an operator reading the page see the same facts.
 *
 * Security notes:
 *  - This is the **HTML sink** for the mail bodies (CLAUDE.md §A03). Every value
 *    reaching it — hostname, component name, cloud account/instance id, AD
 *    domain — originates in imported inventory (scan uploads, CrowdStrike, AWS
 *    metadata) and is therefore attacker-influenceable. Values are escaped here,
 *    at the point of assembly, never at the point of import.
 *  - The plain-text rendering strips CR/LF for the same reason a log line does:
 *    without it, a crafted hostname can forge additional table rows in the text
 *    part of the message.
 *  - Output is bounded by [MAX_ROWS]. A single recipient mapped to a large AWS
 *    account can be linked to thousands of affected systems, and an unbounded
 *    body is both a mail-server problem and an §A04 unbounded-result bug. The
 *    overflow is disclosed in the table's own footer rather than silently
 *    truncated, so no reader mistakes a capped list for a complete one.
 *
 * Styling is inline because a mail client applies no stylesheet, and because
 * `EmailBroadcastService.sanitizeBroadcastHtml` strips attributes from the
 * *admin-supplied* body — this table is generated server-side at send time and
 * never passes through that safelist.
 */
@Singleton
open class EolFindingTableRenderer {

    /**
     * @param findings rows to render; already scoped to what the recipient may see.
     * @param today reference date for the "in N days" wording.
     * @param includeComponent add the component-name column. Off for the product
     *   broadcast, where every row is the same product and the column would be a
     *   constant; on for the owner notification, which spans many products.
     * @param totalCount how many rows exist in total. Defaults to what was passed
     *   in; callers that already paged at the query (§A04) pass the real count so
     *   the overflow notice can name it instead of understating it.
     * @return an HTML `<table>`, or the empty string when there is nothing to show.
     */
    open fun renderHtml(
        findings: List<EolFinding>,
        today: LocalDate = LocalDate.now(),
        includeComponent: Boolean = false,
        totalCount: Int = findings.size
    ): String {
        if (findings.isEmpty()) return ""

        val columns = columns(includeComponent)
        val rows = sortForDisplay(findings)
        val shown = rows.take(MAX_ROWS)
        val builder = StringBuilder()

        builder.append("<table style=\"$TABLE_STYLE\">")
        builder.append("<thead><tr>")
        columns.forEach { builder.append("<th style=\"$HEADER_CELL_STYLE\">").append(it).append("</th>") }
        builder.append("</tr></thead><tbody>")

        for (finding in shown) {
            builder.append("<tr>")
            cellsFor(finding, today, includeComponent).forEach {
                builder.append("<td style=\"$BODY_CELL_STYLE\">").append(escapeHtml(it)).append("</td>")
            }
            builder.append("</tr>")
        }

        builder.append("</tbody>")
        val omitted = omittedCount(totalCount, shown.size)
        if (omitted > 0) {
            builder.append("<tfoot><tr><td colspan=\"").append(columns.size).append("\" style=\"$FOOTER_CELL_STYLE\">")
                .append(escapeHtml(overflowNotice(omitted)))
                .append("</td></tr></tfoot>")
        }
        builder.append("</table>")
        return builder.toString()
    }

    /**
     * Plain-text counterpart of [renderHtml], for the `text/plain` alternative
     * part. Rendered as one indented block per row rather than aligned columns:
     * hostnames and instance ids vary wildly in width, and a column layout that
     * wraps in the reader's client is worse than no column layout at all.
     */
    open fun renderText(
        findings: List<EolFinding>,
        today: LocalDate = LocalDate.now(),
        includeComponent: Boolean = false,
        totalCount: Int = findings.size
    ): String {
        if (findings.isEmpty()) return ""

        val columns = columns(includeComponent)
        val rows = sortForDisplay(findings)
        val shown = rows.take(MAX_ROWS)
        val builder = StringBuilder()

        for (finding in shown) {
            val cells = cellsFor(finding, today, includeComponent)
            builder.append("- ").append(sanitizeForText(cells[0])).append('\n')
            for (index in 1 until columns.size) {
                builder.append("    ").append(columns[index]).append(": ")
                    .append(sanitizeForText(cells[index])).append('\n')
            }
            builder.append('\n')
        }

        val omitted = omittedCount(totalCount, shown.size)
        if (omitted > 0) {
            builder.append(overflowNotice(omitted)).append('\n')
        }
        return builder.toString()
    }

    /**
     * Soonest deadline first, so the most urgent system is the one the recipient
     * reads before losing interest. Findings with no published date sort last —
     * they are already EOL but carry no deadline to act on.
     */
    private fun sortForDisplay(findings: List<EolFinding>): List<EolFinding> =
        findings.sortedWith(
            compareBy<EolFinding> { it.eolDate ?: LocalDate.MAX }
                .thenBy { it.assetName?.lowercase() ?: "" }
                .thenBy { it.componentName.lowercase() }
        )

    /** One string per entry in [columns], in the same order. */
    private fun cellsFor(finding: EolFinding, today: LocalDate, includeComponent: Boolean): List<String> = listOfNotNull(
        finding.assetName.orDash(),
        finding.componentName.orDash().takeIf { includeComponent },
        subjectLabel(finding.subjectType),
        finding.cloudAccountId.orDash(),
        finding.cloudInstanceId.orDash(),
        finding.adDomain.orDash(),
        finding.componentVersion.orDash(),
        finding.eolCycle.orDash(),
        describeDeadline(finding, today),
        statusLabel(finding.status)
    )

    private fun omittedCount(totalCount: Int, shown: Int): Int = (totalCount - shown).coerceAtLeast(0)

    private fun overflowNotice(omitted: Int): String =
        "and $omitted further affected system(s) not listed here — open SecMan for the complete list."

    /** Mirrors `eolFormat.ts` `subjectLabel`; keep the two in step. */
    private fun subjectLabel(subjectType: EolSubjectType): String = when (subjectType) {
        EolSubjectType.ASSET_OS -> "Operating system"
        EolSubjectType.ASSET_PRODUCT -> "Installed software"
        EolSubjectType.REPOSITORY_COMPONENT -> "Repository dependency"
    }

    /** Mirrors `eolFormat.ts` `statusBadge().label`; keep the two in step. */
    private fun statusLabel(status: EolStatus): String = when (status) {
        EolStatus.EOL -> "End of life"
        EolStatus.APPROACHING_EOL -> "Approaching EOL"
        EolStatus.SUPPORTED -> "Supported"
    }

    /**
     * A null date means upstream flagged the cycle end-of-life without publishing
     * one. That must read as "already end of life", never as "0 days left" —
     * which would claim a precision the catalogue does not give us.
     */
    private fun describeDeadline(finding: EolFinding, today: LocalDate): String {
        val date = finding.eolDate
            ?: return if (finding.status == EolStatus.EOL) "already end of life (no date published)" else "unknown"
        val formatted = date.format(DateTimeFormatter.ISO_LOCAL_DATE)
        return when {
            date.isAfter(today) -> "$formatted (in ${ChronoUnit.DAYS.between(today, date)} days)"
            date.isEqual(today) -> "$formatted (today)"
            else -> "$formatted (already end of life)"
        }
    }

    private fun String?.orDash(): String = this?.trim()?.takeIf { it.isNotEmpty() } ?: "-"

    private fun escapeHtml(value: String): String = value
        .take(MAX_FIELD_LENGTH)
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")

    private fun sanitizeForText(value: String): String =
        value.replace(Regex("[\\r\\n]+"), " ").take(MAX_FIELD_LENGTH)

    /** Header row; [BASE_COLUMNS] with "Component" spliced in after "System". */
    private fun columns(includeComponent: Boolean): List<String> =
        if (includeComponent) listOf(BASE_COLUMNS[0], "Component") + BASE_COLUMNS.drop(1) else BASE_COLUMNS

    companion object {
        /**
         * Same columns, same order, as the "Affected systems" table in
         * `EolProductAssetsPage.tsx` and its CSV export.
         */
        val BASE_COLUMNS = listOf(
            "System", "Type", "Cloud account", "Cloud instance",
            "AD domain", "Version", "Release cycle", "End of support", "Status"
        )

        /** Upper bound on rows per message; the remainder is disclosed, not dropped silently. */
        const val MAX_ROWS = 200

        private const val MAX_FIELD_LENGTH = 512

        private const val TABLE_STYLE =
            "border-collapse:collapse;width:100%;font-family:Arial,sans-serif;font-size:12px;color:#333;"
        private const val HEADER_CELL_STYLE =
            "border:1px solid #d9d9d9;padding:6px 8px;background-color:#f4f4f4;text-align:left;font-weight:bold;"
        private const val BODY_CELL_STYLE =
            "border:1px solid #d9d9d9;padding:6px 8px;text-align:left;vertical-align:top;"
        private const val FOOTER_CELL_STYLE =
            "border:1px solid #d9d9d9;padding:6px 8px;font-style:italic;color:#666;"
    }
}
