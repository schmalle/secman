package com.secman.service

import com.secman.config.UserCleanupConfig
import com.secman.domain.User
import io.micronaut.context.annotation.Value
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/** Sends the "your account will be deleted on or after …" mail of the inactive-user cleanup. */
@Singleton
open class InactiveUserWarningMailer(
    private val emailService: EmailService,
    private val renderer: EmailTemplateRenderer,
    private val config: UserCleanupConfig,
    @Value("\${app.frontend.base-url:http://localhost:4321}") private val frontendBaseUrl: String
) {
    private val log = LoggerFactory.getLogger(InactiveUserWarningMailer::class.java)

    /** True only when the mail was handed to the SMTP server; the caller records the warning on true. */
    open fun send(user: User, deletionDate: Instant): Boolean {
        val values = mapOf(
            "username" to user.username,
            "deletionDate" to DATE.format(deletionDate),
            "inactiveDays" to config.inactiveDays.toString(),
            "loginUrl" to frontendBaseUrl.trimEnd('/') + "/login"
        )
        return try {
            emailService.sendEmailWithInlineImages(
                to = user.email,
                subject = "Your SecMan account will be deleted on or after ${values["deletionDate"]}",
                textContent = renderer.render(renderer.readText(TEMPLATE), values, escape = false),
                htmlContent = renderer.render(renderer.readHtml(TEMPLATE), values, escape = true),
                inlineImages = renderer.loadLogoInlineImage()
            ).get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (e: Exception) {
            log.warn("Inactive-account warning to userId={} failed: {}", user.id, e.javaClass.simpleName)
            false
        }
    }

    private companion object {
        const val TEMPLATE = "inactive-account-warning"
        const val SEND_TIMEOUT_SECONDS = 120L
        val DATE: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneOffset.UTC)
    }
}
