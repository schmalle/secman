package com.secman.service

import com.secman.domain.Requirement
import com.secman.dto.AssessmentContext
import com.secman.dto.FewShotExample
import jakarta.inject.Singleton
import java.util.regex.Pattern

/**
 * Feature 088 — AI-Assisted Risk Assessment Answers.
 *
 * Builds the user-side message of the prompt for one requirement. The
 * system prompt comes from a classpath resource (loaded by
 * ComplianceAssistantService).
 *
 * The big responsibility here is REDACTION (NFR-4): we never send owner
 * emails, IP addresses, or internal-hostname URLs to the LLM, regardless of
 * how the caller assembled the context. Defence in depth — a unit test
 * asserts the redaction holds even when the inputs explicitly contain those.
 */
@Singleton
class PromptBuilder {

    private companion object {
        // Email — RFC-5322-ish; greedy enough for redaction, conservative enough
        // not to maul ordinary words. Always replaces with the marker below.
        private val EMAIL = Pattern.compile(
            "\\b[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}\\b"
        )
        // IPv4 (4 octets, 0..255). Loose-but-safe regex; we err on the side of
        // over-redaction since false positives are harmless to the prompt.
        private val IPV4 = Pattern.compile(
            "\\b(?:(?:25[0-5]|2[0-4]\\d|[01]?\\d?\\d)\\.){3}(?:25[0-5]|2[0-4]\\d|[01]?\\d?\\d)\\b"
        )
        // Internal hosts — domains that look private. .internal, .local,
        // .corp, .lan, .intranet — common conventions. Cheap; if someone
        // uses a different convention we miss it, but the caller is also
        // required not to pass internal URLs in the first place.
        private val INTERNAL_URL = Pattern.compile(
            "https?://[^\\s]*(\\.internal|\\.local|\\.corp|\\.lan|\\.intranet)\\b[^\\s]*",
            Pattern.CASE_INSENSITIVE
        )
        private const val REDACTED = "[REDACTED]"
    }

    fun build(requirement: Requirement, context: AssessmentContext): String {
        val sb = StringBuilder(2048)
        sb.append("# REQUIREMENT\n")
        sb.append("Short requirement: ").append(requirement.shortreq).append('\n')
        requirement.details?.takeIf { it.isNotBlank() }?.let {
            sb.append("Details: ").append(it).append('\n')
        }
        requirement.motivation?.takeIf { it.isNotBlank() }?.let {
            sb.append("Motivation: ").append(it).append('\n')
        }
        requirement.example?.takeIf { it.isNotBlank() }?.let {
            sb.append("Example: ").append(it).append('\n')
        }
        requirement.norm?.takeIf { it.isNotBlank() }?.let { norm ->
            sb.append("Norm: ").append(norm)
            requirement.chapter?.takeIf { it.isNotBlank() }?.let { sb.append(" — chapter ").append(it) }
            sb.append('\n')
        }
        if (requirement.norms.isNotEmpty()) {
            val names = requirement.norms.joinToString(", ") { n -> buildString {
                append(n.name); n.version?.let { append(" v"); append(it) }
            }}
            sb.append("Linked norms: ").append(names).append('\n')
        }

        sb.append("\n# CONTEXT\n")
        sb.append("Basis: ").append(context.basisType).append(" — ").append(context.basisLabel).append('\n')
        context.assetType?.takeIf { it.isNotBlank() }?.let { sb.append("Asset type: ").append(it).append('\n') }
        if (context.assetGroups.isNotEmpty()) sb.append("Groups: ").append(context.assetGroups.joinToString(", ")).append('\n')
        context.osVersion?.takeIf { it.isNotBlank() }?.let { sb.append("OS version: ").append(it).append('\n') }
        context.cloudAccountId?.takeIf { it.isNotBlank() }?.let { sb.append("Cloud account ID: ").append(it).append('\n') }
        context.demandDescription?.takeIf { it.isNotBlank() }?.let { sb.append("Demand description: ").append(it).append('\n') }
        if (context.useCases.isNotEmpty()) sb.append("Use cases: ").append(context.useCases.joinToString(", ")).append('\n')

        if (context.fewShotExamples.isNotEmpty()) {
            sb.append("\n# RELATED ANSWERS ALREADY GIVEN IN THIS ASSESSMENT\n")
            context.fewShotExamples.take(3).forEachIndexed { i, ex -> appendFewShot(sb, i + 1, ex) }
        }

        sb.append("\nProduce the JSON object now — no preamble.")
        return redact(sb.toString())
    }

    private fun appendFewShot(sb: StringBuilder, n: Int, ex: FewShotExample) {
        sb.append("Example ").append(n).append(":\n")
        sb.append("  Requirement: ").append(ex.requirementText.take(200)).append('\n')
        sb.append("  Answer: ").append(ex.answer).append('\n')
        ex.comment?.takeIf { it.isNotBlank() }?.let {
            sb.append("  Comment: ").append(it.take(300)).append('\n')
        }
    }

    /**
     * Strip PII the caller may have leaked in.
     * Visible for testing.
     */
    internal fun redact(input: String): String {
        var s = input
        s = INTERNAL_URL.matcher(s).replaceAll(REDACTED)
        s = EMAIL.matcher(s).replaceAll(REDACTED)
        s = IPV4.matcher(s).replaceAll(REDACTED)
        return s
    }
}
