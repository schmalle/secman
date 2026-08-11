package com.secman.service

import jakarta.inject.Singleton
import org.slf4j.LoggerFactory

/**
 * Renders the classpath email templates under `/email-templates/`.
 *
 * Extracted verbatim from [AwsAccountRiskAssessmentService], which grew these five helpers
 * privately and now shares them with [AccountOnboardingService]. Behaviour is unchanged —
 * the existing `aws-account-risk-assessment-*` mails must render byte-identically, which is
 * what `AwsAccountRiskAssessmentServiceTest` continues to assert.
 *
 * Why classpath files and not DB-backed templates: the bodies are versioned with the code
 * that supplies their placeholders, so a template and its renderer cannot drift apart at
 * runtime, and there is no stored-HTML sink to sanitize. The Thymeleaf path in
 * [EmailTemplateService] is a different mechanism used by other features; do not mix them.
 */
@Singleton
open class EmailTemplateRenderer {

    private val log = LoggerFactory.getLogger(EmailTemplateRenderer::class.java)

    companion object {
        private const val TEMPLATE_DIR = "/email-templates/"
        private const val LOGO_PATH = "/email-templates/SecManLogo.png"

        /** The CID the HTML templates reference for the inline logo. */
        const val LOGO_CID = "secman-logo"

        /**
         * Every template basename this renderer will load, by config key or otherwise.
         *
         * A closed allowlist, not a naming convention. `secman.account-onboarding.*-template`
         * is operator-supplied configuration, and interpolating a configured string into
         * `getResourceAsStream` would be a classpath-traversal read primitive: a value like
         * `../application` would happily return the config file, secrets and all (A08).
         * Adding a template means adding it here, in the same commit as the file.
         */
        val ALLOWED_TEMPLATES: Set<String> = setOf(
            "aws-account-risk-assessment-started",
            "aws-account-risk-assessment-reminder",
            "account-welcome",
            "account-onboarding-questionnaire",
            "account-onboarding-reminder"
        )
    }

    /**
     * Resolve a configured template basename to a loadable one.
     *
     * @throws IllegalArgumentException when the name is not in [ALLOWED_TEMPLATES]. Thrown,
     *         not defaulted: silently falling back to another template would send the owner a
     *         mail about something other than what the operator configured.
     */
    fun requireAllowed(basename: String): String {
        require(basename in ALLOWED_TEMPLATES) {
            "Unknown email template '$basename'. Allowed: ${ALLOWED_TEMPLATES.sorted().joinToString(", ")}"
        }
        return basename
    }

    /** Load `/email-templates/<basename>.html`. [basename] must already be allowlisted. */
    fun readHtml(basename: String): String = readResource("$TEMPLATE_DIR${requireAllowed(basename)}.html")

    /** Load `/email-templates/<basename>.txt`. [basename] must already be allowlisted. */
    fun readText(basename: String): String = readResource("$TEMPLATE_DIR${requireAllowed(basename)}.txt")

    /**
     * Substitute `{placeholder}` tokens.
     *
     * [escape] applies HTML escaping to the *values*, never the template, so a use case named
     * `<b>x</b>` cannot inject markup into the HTML part. The plain-text part must stay
     * unescaped or readers see `&amp;`.
     */
    fun render(template: String, values: Map<String, String>, escape: Boolean): String =
        values.entries.fold(template) { acc, (key, value) ->
            acc.replace("{$key}", if (escape) escapeHtml(value) else value)
        }

    /**
     * Render or strip a `{name}…{/name}` block, consuming one trailing newline so a stripped
     * block leaves no blank hole. Non-greedy, so repeated blocks render independently.
     */
    fun renderConditionalBlock(template: String, name: String, include: Boolean): String {
        val pattern = Regex(
            Regex.escape("{$name}") + "(.*?)" + Regex.escape("{/$name}") + "\\n?",
            setOf(RegexOption.DOT_MATCHES_ALL)
        )
        return pattern.replace(template) { match -> if (include) match.groupValues[1] else "" }
    }

    /** The inline SecMan logo, keyed by CID. Empty when unavailable — see [loadLogoInlineImage]. */
    fun loadLogoInlineImage(): Map<String, Pair<ByteArray, String>> =
        try {
            javaClass.getResourceAsStream(LOGO_PATH)?.readAllBytes()
                ?.let { mapOf(LOGO_CID to (it to "image/png")) }
                ?: emptyMap()
        } catch (e: Exception) {
            // A missing logo must not cost the recipient their notification.
            log.warn("Failed to load SecManLogo.png: {}", e.message)
            emptyMap()
        }

    fun escapeHtml(text: String): String =
        text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")

    private fun readResource(path: String): String {
        val stream = javaClass.getResourceAsStream(path)
            ?: throw IllegalStateException("Email template not found on classpath: $path")
        return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }
}
