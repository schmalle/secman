package com.secman.service

import com.secman.config.AppConfig
import com.secman.domain.AwsAccountSharingCreatedEvent
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.util.concurrent.CompletableFuture

/**
 * Builds and dispatches the email sent to a sharing rule's target user
 * when an AwsAccountSharing has just been created.
 *
 * Pattern matches ExceptionRequestNotificationService:
 *  - never throws upward
 *  - returns CompletableFuture<Boolean>
 *  - email I/O wrapped via EmailService.sendEmailWithInlineImages
 *
 * The input event carries primitives only — there are no Hibernate
 * lazy associations to worry about.
 */
@Singleton
open class AwsAccountSharingNotificationService(
    private val emailService: EmailService,
    private val appConfig: AppConfig,
) {
    private val log = LoggerFactory.getLogger(AwsAccountSharingNotificationService::class.java)

    companion object {
        private const val SUBJECT = "AWS account access shared with you in SecMan"
        private const val HTML_TEMPLATE = "/email-templates/aws-sharing-granted.html"
        private const val TEXT_TEMPLATE = "/email-templates/aws-sharing-granted.txt"
        private const val LOGO_PATH = "/email-templates/SecManLogo.png"
        private const val ASSETS_PATH = "/assets"
    }

    open fun notifyTargetOfNewShare(event: AwsAccountSharingCreatedEvent): CompletableFuture<Boolean> {
        return try {
            val htmlTemplate = readResource(HTML_TEMPLATE)
            val textTemplate = readResource(TEXT_TEMPLATE)
            val assetsUrl = appConfig.backend.baseUrl.trimEnd('/') + ASSETS_PATH
            // Use the same env-driven base as assetsUrl. The frontend.baseUrl
            // default is localhost:4321 and isn't backed by an env override —
            // in real deployments nginx fronts both API and UI under the same
            // host (SECMAN_BACKEND_URL → e.g. https://secman.covestro.net), so
            // sourcing the login URL from backend.baseUrl keeps both links on
            // the public host without adding a second config knob.
            val loginUrl = appConfig.backend.baseUrl.trimEnd('/')

            val htmlBody = substitute(htmlTemplate, event, assetsUrl, loginUrl)
            val textBody = substitute(textTemplate, event, assetsUrl, loginUrl)
            val inlineImages = loadLogoInlineImage()

            val future = emailService.sendEmailWithInlineImages(
                to = event.targetUserEmail,
                subject = SUBJECT,
                textContent = textBody,
                htmlContent = htmlBody,
                inlineImages = inlineImages,
            )

            future.handle { sent, ex ->
                if (ex != null) {
                    log.error("Failed to send AWS sharing notification (sharingId={}): {}",
                        event.sharingId, ex.message)
                    false
                } else {
                    if (sent == true) {
                        log.info("Sent AWS sharing notification to {} (sharingId={})",
                            event.targetUserEmail, event.sharingId)
                    } else {
                        log.warn("AWS sharing notification not delivered to {} (sharingId={})",
                            event.targetUserEmail, event.sharingId)
                    }
                    sent == true
                }
            }
        } catch (e: Exception) {
            log.error("Unable to build AWS sharing notification (sharingId={}): {}",
                event.sharingId, e.message, e)
            CompletableFuture.completedFuture(false)
        }
    }

    private fun substitute(template: String, e: AwsAccountSharingCreatedEvent, assetsUrl: String, loginUrl: String): String {
        // Render-or-strip the conditional onboarding block before doing
        // simple field substitutions, so {loginUrl} inside the block is
        // resolved in the same pass.
        val withBlockRendered = renderConditionalBlock(template, "ifNewAccount", e.targetUserWasJustCreated)

        return withBlockRendered
            .replace("{targetUsername}", e.targetUsername)
            .replace("{targetUserEmail}", e.targetUserEmail)
            .replace("{sourceUserEmail}", e.sourceUserEmail)
            .replace("{sharedAwsAccountCount}", e.sharedAwsAccountCount.toString())
            .replace("{createdByEmail}", e.createdByEmail)
            .replace("{createdAtIso}", e.createdAtIso)
            .replace("{assetsUrl}", assetsUrl)
            .replace("{loginUrl}", loginUrl)
    }

    /**
     * Hand-rolled conditional block: replaces every
     *   {name}…{/name}
     * pair with either its inner content (when [include] is true) or
     * an empty string. The regex captures one trailing newline after
     * the close marker (when present) so stripping the block doesn't
     * leave an extra blank line in the rendered output. The block is
     * non-greedy so multiple blocks on the same template each render
     * independently.
     */
    private fun renderConditionalBlock(template: String, name: String, include: Boolean): String {
        val open = "{$name}"
        val close = "{/$name}"
        val pattern = Regex(
            Regex.escape(open) + "(.*?)" + Regex.escape(close) + "\\n?",
            setOf(RegexOption.DOT_MATCHES_ALL)
        )
        return pattern.replace(template) { match ->
            if (include) match.groupValues[1] else ""
        }
    }

    /**
     * Test seam — production calls the private `substitute` directly.
     * Exposed `internal` so unit tests exercise the rendering without I/O.
     */
    internal fun substituteForTest(
        template: String,
        event: AwsAccountSharingCreatedEvent,
        assetsUrl: String,
        loginUrl: String,
    ): String = substitute(template, event, assetsUrl, loginUrl)

    private fun readResource(path: String): String {
        val stream = javaClass.getResourceAsStream(path)
            ?: throw IllegalStateException("Email template not found on classpath: $path")
        return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    private fun loadLogoInlineImage(): Map<String, Pair<ByteArray, String>> {
        return try {
            val bytes = javaClass.getResourceAsStream(LOGO_PATH)?.readAllBytes()
            if (bytes != null) mapOf("secman-logo" to (bytes to "image/png")) else emptyMap()
        } catch (e: Exception) {
            log.warn("Failed to load SecManLogo.png: {}", e.message)
            emptyMap()
        }
    }
}
