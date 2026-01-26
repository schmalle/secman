package com.secman.service

import com.secman.domain.AlignmentReviewer
import com.secman.domain.AlignmentSession
import com.secman.repository.AlignmentReviewerRepository
import jakarta.inject.Singleton
import jakarta.transaction.Transactional
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.thymeleaf.context.Context
import java.io.StringWriter

/**
 * Service for sending alignment-related email notifications.
 * Feature: 068-requirements-alignment-process
 */
@Singleton
open class AlignmentEmailService(
    private val emailService: EmailService,
    private val alignmentReviewerRepository: AlignmentReviewerRepository,
    private val emailTemplateService: EmailTemplateService,
    private val appSettingsService: AppSettingsService
) {
    private val logger = LoggerFactory.getLogger(AlignmentEmailService::class.java)

    /**
     * Send review request emails to all reviewers in a session.
     *
     * @param session The alignment session
     * @param reviewers List of reviewers to notify
     * @return Map of reviewer IDs to success status
     */
    @Transactional
    open fun sendReviewRequestEmails(
        session: AlignmentSession,
        reviewers: List<AlignmentReviewer>
    ): Map<Long, Boolean> {
        logger.info("Sending review request emails for session {} to {} reviewers",
            session.id, reviewers.size)

        val results = mutableMapOf<Long, Boolean>()

        reviewers.forEach { reviewer ->
            try {
                val success = sendReviewRequestEmail(session, reviewer)
                results[reviewer.id!!] = success

                if (success) {
                    reviewer.markNotified()
                    alignmentReviewerRepository.update(reviewer)
                }
            } catch (e: Exception) {
                logger.error("Failed to send review request to {}: {}",
                    reviewer.user.email, e.message)
                results[reviewer.id!!] = false
            }
        }

        val successCount = results.values.count { it }
        logger.info("Sent {} of {} review request emails successfully",
            successCount, reviewers.size)

        return results
    }

    /**
     * Send a review request email to a single reviewer.
     */
    private fun sendReviewRequestEmail(
        session: AlignmentSession,
        reviewer: AlignmentReviewer
    ): Boolean {
        val release = session.release
        val user = reviewer.user

        val baseUrl = appSettingsService.getBaseUrl()
        val reviewUrl = "$baseUrl/alignment/review/${reviewer.reviewToken}"
        val subject = "[Action Required] Review Requirements for Release ${release.version}"

        val htmlContent = buildReviewRequestHtml(
            recipientName = user.username,
            releaseName = release.name,
            releaseVersion = release.version,
            changedCount = session.changedRequirementsCount,
            reviewUrl = reviewUrl,
            initiatorName = session.initiatedBy.username
        )

        val textContent = buildReviewRequestText(
            recipientName = user.username,
            releaseName = release.name,
            releaseVersion = release.version,
            changedCount = session.changedRequirementsCount,
            reviewUrl = reviewUrl,
            initiatorName = session.initiatedBy.username
        )

        return runBlocking {
            try {
                emailService.sendEmail(user.email, subject, textContent, htmlContent).get()
            } catch (e: Exception) {
                logger.error("Failed to send email to {}: {}", user.email, e.message)
                false
            }
        }
    }

    /**
     * Send reminder emails to incomplete reviewers.
     *
     * @param session The alignment session
     * @param reviewers List of incomplete reviewers to remind
     * @return Map of reviewer IDs to success status
     */
    @Transactional
    open fun sendReminderEmails(
        session: AlignmentSession,
        reviewers: List<AlignmentReviewer>
    ): Map<Long, Boolean> {
        logger.info("Sending reminder emails for session {} to {} reviewers",
            session.id, reviewers.size)

        val results = mutableMapOf<Long, Boolean>()

        reviewers.forEach { reviewer ->
            try {
                val success = sendReminderEmail(session, reviewer)
                results[reviewer.id!!] = success

                if (success) {
                    reviewer.markReminded()
                    alignmentReviewerRepository.update(reviewer)
                }
            } catch (e: Exception) {
                logger.error("Failed to send reminder to {}: {}",
                    reviewer.user.email, e.message)
                results[reviewer.id!!] = false
            }
        }

        val successCount = results.values.count { it }
        logger.info("Sent {} of {} reminder emails successfully",
            successCount, reviewers.size)

        return results
    }

    /**
     * Send a reminder email to a single reviewer.
     */
    private fun sendReminderEmail(
        session: AlignmentSession,
        reviewer: AlignmentReviewer
    ): Boolean {
        val release = session.release
        val user = reviewer.user

        val baseUrl = appSettingsService.getBaseUrl()
        val reviewUrl = "$baseUrl/alignment/review/${reviewer.reviewToken}"
        val subject = "[Reminder] Review Required for Release ${release.version}"

        val htmlContent = buildReminderHtml(
            recipientName = user.username,
            releaseName = release.name,
            releaseVersion = release.version,
            reviewedCount = reviewer.reviewedCount,
            totalCount = session.changedRequirementsCount,
            reviewUrl = reviewUrl,
            reminderNumber = reviewer.reminderCount + 1
        )

        val textContent = buildReminderText(
            recipientName = user.username,
            releaseName = release.name,
            releaseVersion = release.version,
            reviewedCount = reviewer.reviewedCount,
            totalCount = session.changedRequirementsCount,
            reviewUrl = reviewUrl,
            reminderNumber = reviewer.reminderCount + 1
        )

        return runBlocking {
            try {
                emailService.sendEmail(user.email, subject, textContent, htmlContent).get()
            } catch (e: Exception) {
                logger.error("Failed to send reminder to {}: {}", user.email, e.message)
                false
            }
        }
    }

    // ========== HTML Email Builders ==========

    private fun buildReviewRequestHtml(
        recipientName: String,
        releaseName: String,
        releaseVersion: String,
        changedCount: Int,
        reviewUrl: String,
        initiatorName: String
    ): String {
        return """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>Requirements Review Request</title>
            </head>
            <body style="font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif; line-height: 1.6; color: #1a1a2e; margin: 0; padding: 0; background-color: #f8f9fa;">
                <div style="max-width: 600px; margin: 0 auto; padding: 40px 20px;">
                    <div style="background: linear-gradient(135deg, #667eea 0%, #764ba2 100%); padding: 30px; border-radius: 12px 12px 0 0; text-align: center;">
                        <h1 style="color: white; margin: 0; font-size: 24px; font-weight: 600;">Requirements Alignment</h1>
                        <p style="color: rgba(255,255,255,0.9); margin: 10px 0 0 0; font-size: 14px;">Review Required</p>
                    </div>

                    <div style="background: white; padding: 30px; border-radius: 0 0 12px 12px; box-shadow: 0 4px 6px rgba(0, 0, 0, 0.1);">
                        <p style="font-size: 16px; margin-bottom: 20px;">Hello <strong>$recipientName</strong>,</p>

                        <p style="margin-bottom: 20px;">You have been invited by <strong>$initiatorName</strong> to review requirement changes for an upcoming release.</p>

                        <div style="background: #f8f9fa; border-radius: 8px; padding: 20px; margin: 20px 0;">
                            <h3 style="margin: 0 0 15px 0; color: #495057; font-size: 14px; text-transform: uppercase; letter-spacing: 0.5px;">Release Details</h3>
                            <table style="width: 100%; border-collapse: collapse;">
                                <tr>
                                    <td style="padding: 8px 0; color: #6c757d; width: 40%;">Release Name:</td>
                                    <td style="padding: 8px 0; font-weight: 600;">$releaseName</td>
                                </tr>
                                <tr>
                                    <td style="padding: 8px 0; color: #6c757d;">Version:</td>
                                    <td style="padding: 8px 0; font-weight: 600;">$releaseVersion</td>
                                </tr>
                                <tr>
                                    <td style="padding: 8px 0; color: #6c757d;">Requirements to Review:</td>
                                    <td style="padding: 8px 0; font-weight: 600; color: #667eea;">$changedCount changes</td>
                                </tr>
                            </table>
                        </div>

                        <div style="text-align: center; margin: 30px 0;">
                            <a href="$reviewUrl" style="display: inline-block; background: linear-gradient(135deg, #667eea 0%, #764ba2 100%); color: white; text-decoration: none; padding: 14px 32px; border-radius: 8px; font-weight: 600; font-size: 16px; box-shadow: 0 4px 15px rgba(102, 126, 234, 0.4);">
                                Start Review
                            </a>
                        </div>

                        <p style="font-size: 14px; color: #6c757d; margin-top: 20px;">
                            For each requirement, you'll be asked to assess whether the change is:
                        </p>
                        <ul style="color: #6c757d; font-size: 14px;">
                            <li><strong style="color: #28a745;">Minor</strong> - Acceptable change with minor impact</li>
                            <li><strong style="color: #ffc107;">Major</strong> - Significant concern requiring attention</li>
                            <li><strong style="color: #dc3545;">NOK</strong> - Not acceptable, blocks release</li>
                        </ul>

                        <hr style="border: none; border-top: 1px solid #e9ecef; margin: 30px 0;">

                        <p style="font-size: 12px; color: #adb5bd; text-align: center; margin: 0;">
                            This is an automated notification from SecMan.<br>
                            If you cannot access the link, copy and paste this URL: $reviewUrl
                        </p>
                    </div>
                </div>
            </body>
            </html>
        """.trimIndent()
    }

    private fun buildReviewRequestText(
        recipientName: String,
        releaseName: String,
        releaseVersion: String,
        changedCount: Int,
        reviewUrl: String,
        initiatorName: String
    ): String {
        return """
            REQUIREMENTS ALIGNMENT - REVIEW REQUIRED
            =========================================

            Hello $recipientName,

            You have been invited by $initiatorName to review requirement changes for an upcoming release.

            Release Details:
            - Release Name: $releaseName
            - Version: $releaseVersion
            - Requirements to Review: $changedCount changes

            Please review the changes at:
            $reviewUrl

            For each requirement, assess whether the change is:
            - Minor: Acceptable change with minor impact
            - Major: Significant concern requiring attention
            - NOK: Not acceptable, blocks release

            ---
            This is an automated notification from SecMan.
        """.trimIndent()
    }

    private fun buildReminderHtml(
        recipientName: String,
        releaseName: String,
        releaseVersion: String,
        reviewedCount: Int,
        totalCount: Int,
        reviewUrl: String,
        reminderNumber: Int
    ): String {
        val progressPercent = if (totalCount > 0) (reviewedCount * 100 / totalCount) else 0
        val remaining = totalCount - reviewedCount

        return """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>Requirements Review Reminder</title>
            </head>
            <body style="font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif; line-height: 1.6; color: #1a1a2e; margin: 0; padding: 0; background-color: #f8f9fa;">
                <div style="max-width: 600px; margin: 0 auto; padding: 40px 20px;">
                    <div style="background: linear-gradient(135deg, #f093fb 0%, #f5576c 100%); padding: 30px; border-radius: 12px 12px 0 0; text-align: center;">
                        <h1 style="color: white; margin: 0; font-size: 24px; font-weight: 600;">Reminder #$reminderNumber</h1>
                        <p style="color: rgba(255,255,255,0.9); margin: 10px 0 0 0; font-size: 14px;">Requirements Review Pending</p>
                    </div>

                    <div style="background: white; padding: 30px; border-radius: 0 0 12px 12px; box-shadow: 0 4px 6px rgba(0, 0, 0, 0.1);">
                        <p style="font-size: 16px; margin-bottom: 20px;">Hello <strong>$recipientName</strong>,</p>

                        <p style="margin-bottom: 20px;">This is a friendly reminder that your review for <strong>$releaseName v$releaseVersion</strong> is still pending.</p>

                        <div style="background: #f8f9fa; border-radius: 8px; padding: 20px; margin: 20px 0;">
                            <h3 style="margin: 0 0 15px 0; color: #495057; font-size: 14px; text-transform: uppercase; letter-spacing: 0.5px;">Your Progress</h3>
                            <div style="background: #e9ecef; border-radius: 4px; height: 8px; margin-bottom: 10px;">
                                <div style="background: linear-gradient(135deg, #667eea 0%, #764ba2 100%); width: $progressPercent%; height: 100%; border-radius: 4px;"></div>
                            </div>
                            <p style="margin: 0; color: #6c757d; font-size: 14px;">
                                <strong>$reviewedCount</strong> of <strong>$totalCount</strong> requirements reviewed
                                ${if (remaining > 0) "(<strong>$remaining</strong> remaining)" else ""}
                            </p>
                        </div>

                        <div style="text-align: center; margin: 30px 0;">
                            <a href="$reviewUrl" style="display: inline-block; background: linear-gradient(135deg, #f093fb 0%, #f5576c 100%); color: white; text-decoration: none; padding: 14px 32px; border-radius: 8px; font-weight: 600; font-size: 16px; box-shadow: 0 4px 15px rgba(245, 87, 108, 0.4);">
                                Continue Review
                            </a>
                        </div>

                        <hr style="border: none; border-top: 1px solid #e9ecef; margin: 30px 0;">

                        <p style="font-size: 12px; color: #adb5bd; text-align: center; margin: 0;">
                            This is an automated notification from SecMan.<br>
                            If you cannot access the link, copy and paste this URL: $reviewUrl
                        </p>
                    </div>
                </div>
            </body>
            </html>
        """.trimIndent()
    }

    private fun buildReminderText(
        recipientName: String,
        releaseName: String,
        releaseVersion: String,
        reviewedCount: Int,
        totalCount: Int,
        reviewUrl: String,
        reminderNumber: Int
    ): String {
        val remaining = totalCount - reviewedCount

        return """
            REMINDER #$reminderNumber - REQUIREMENTS REVIEW PENDING
            =====================================================

            Hello $recipientName,

            This is a friendly reminder that your review for $releaseName v$releaseVersion is still pending.

            Your Progress:
            - Reviewed: $reviewedCount of $totalCount requirements
            - Remaining: $remaining requirements

            Please continue your review at:
            $reviewUrl

            ---
            This is an automated notification from SecMan.
        """.trimIndent()
    }
}
