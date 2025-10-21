package com.secman.service

import com.secman.domain.User
import com.secman.domain.VulnerabilityExceptionRequest
import com.secman.repository.UserRepository
import io.micronaut.scheduling.annotation.Async
import jakarta.inject.Inject
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.time.format.DateTimeFormatter
import java.util.concurrent.CompletableFuture

/**
 * Service for sending email notifications related to vulnerability exception requests.
 *
 * **Notification Types**:
 * - New Request: Notify ADMIN/SECCHAMPION when user creates PENDING request
 * - Approval: Notify requester when request approved
 * - Rejection: Notify requester when request rejected (includes review comment)
 * - Expiration Reminder: Notify requester 7 days before exception expires
 *
 * **Non-Blocking Design**:
 * - All methods use @Async annotation for background email sending
 * - Failures are logged but do not throw exceptions (email failures should not block workflow)
 * - Returns CompletableFuture for testing and monitoring
 *
 * Feature: 031-vuln-exception-approval
 * User Story 6: Email Notifications (P3)
 * Phase 10: Email Notifications
 * Reference: spec.md acceptance scenarios US6-1, US6-2, US6-3
 */
@Singleton
open class ExceptionRequestNotificationService(
    @Inject private val emailService: EmailService,
    @Inject private val userRepository: UserRepository
) {
    private val logger = LoggerFactory.getLogger(ExceptionRequestNotificationService::class.java)

    companion object {
        private val DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        private const val DASHBOARD_URL = "/exception-approvals" // Relative URL for dashboard
    }

    /**
     * Notify all ADMIN and SECCHAMPION users of a new exception request.
     *
     * Called after createRequest() when status is PENDING (not auto-approved).
     *
     * @param request The newly created exception request
     * @return CompletableFuture indicating if at least one email was sent successfully
     */
    @Async
    open fun notifyAdminsOfNewRequest(request: VulnerabilityExceptionRequest): CompletableFuture<Boolean> {
        return CompletableFuture.supplyAsync {
            try {
                logger.info("Sending new request notifications for requestId={}", request.id)

                // Find all users with ADMIN or SECCHAMPION roles
                val adminUsers = userRepository.findAll().filter { user ->
                    user.hasRole(User.Role.ADMIN) || user.hasRole(User.Role.SECCHAMPION)
                }

                if (adminUsers.isEmpty()) {
                    logger.warn("No ADMIN or SECCHAMPION users found to notify about request {}", request.id)
                    return@supplyAsync false
                }

                val cveId = request.vulnerability?.vulnerabilityId ?: "Unknown CVE"
                val assetName = request.vulnerability?.asset?.name ?: "Unknown Asset"
                val subject = "New Exception Request: $cveId on $assetName"

                var successCount = 0
                var failureCount = 0

                for (user in adminUsers) {
                    try {
                        val htmlContent = generateNewRequestEmail(request, user.username)
                        val future = emailService.sendHtmlEmail(user.email, subject, htmlContent)
                        val sent = future.get() // Block to ensure delivery attempt

                        if (sent) {
                            successCount++
                            logger.debug("Sent new request notification to {}", user.email)
                        } else {
                            failureCount++
                            logger.warn("Failed to send new request notification to {}", user.email)
                        }
                    } catch (e: Exception) {
                        failureCount++
                        logger.error("Error sending new request notification to {}: {}", user.email, e.message)
                    }
                }

                logger.info("New request notifications sent: {} success, {} failures (requestId={})",
                    successCount, failureCount, request.id)

                return@supplyAsync successCount > 0

            } catch (e: Exception) {
                logger.error("Failed to send new request notifications for requestId={}", request.id, e)
                return@supplyAsync false
            }
        }
    }

    /**
     * Notify requester of request approval.
     *
     * Called after approveRequest() completes successfully.
     *
     * @param request The approved exception request
     * @return CompletableFuture indicating if email was sent successfully
     */
    @Async
    open fun notifyRequesterOfApproval(request: VulnerabilityExceptionRequest): CompletableFuture<Boolean> {
        return CompletableFuture.supplyAsync {
            try {
                val requesterEmail = request.requestedByUser?.email
                if (requesterEmail.isNullOrBlank()) {
                    logger.warn("No requester email for approved request {}", request.id)
                    return@supplyAsync false
                }

                val cveId = request.vulnerability?.vulnerabilityId ?: "Unknown CVE"
                val assetName = request.vulnerability?.asset?.name ?: "Unknown Asset"
                val subject = "Exception Approved: $cveId on $assetName"

                val htmlContent = generateApprovalEmail(request)
                val future = emailService.sendHtmlEmail(requesterEmail, subject, htmlContent)
                val sent = future.get()

                if (sent) {
                    logger.info("Sent approval notification to {} for requestId={}", requesterEmail, request.id)
                } else {
                    logger.warn("Failed to send approval notification to {} for requestId={}", requesterEmail, request.id)
                }

                return@supplyAsync sent

            } catch (e: Exception) {
                logger.error("Failed to send approval notification for requestId={}", request.id, e)
                return@supplyAsync false
            }
        }
    }

    /**
     * Notify requester of request rejection.
     *
     * Called after rejectRequest() completes successfully.
     * Includes review comment explaining rejection reason.
     *
     * @param request The rejected exception request
     * @return CompletableFuture indicating if email was sent successfully
     */
    @Async
    open fun notifyRequesterOfRejection(request: VulnerabilityExceptionRequest): CompletableFuture<Boolean> {
        return CompletableFuture.supplyAsync {
            try {
                val requesterEmail = request.requestedByUser?.email
                if (requesterEmail.isNullOrBlank()) {
                    logger.warn("No requester email for rejected request {}", request.id)
                    return@supplyAsync false
                }

                val cveId = request.vulnerability?.vulnerabilityId ?: "Unknown CVE"
                val assetName = request.vulnerability?.asset?.name ?: "Unknown Asset"
                val subject = "Exception Rejected: $cveId on $assetName"

                val htmlContent = generateRejectionEmail(request)
                val future = emailService.sendHtmlEmail(requesterEmail, subject, htmlContent)
                val sent = future.get()

                if (sent) {
                    logger.info("Sent rejection notification to {} for requestId={}", requesterEmail, request.id)
                } else {
                    logger.warn("Failed to send rejection notification to {} for requestId={}", requesterEmail, request.id)
                }

                return@supplyAsync sent

            } catch (e: Exception) {
                logger.error("Failed to send rejection notification for requestId={}", request.id, e)
                return@supplyAsync false
            }
        }
    }

    /**
     * Notify requester of upcoming exception expiration (7 days warning).
     *
     * Called by scheduled job for APPROVED requests expiring within 7 days.
     *
     * @param request The exception request expiring soon
     * @return CompletableFuture indicating if email was sent successfully
     */
    @Async
    open fun notifyRequesterOfExpiration(request: VulnerabilityExceptionRequest): CompletableFuture<Boolean> {
        return CompletableFuture.supplyAsync {
            try {
                val requesterEmail = request.requestedByUser?.email
                if (requesterEmail.isNullOrBlank()) {
                    logger.warn("No requester email for expiring request {}", request.id)
                    return@supplyAsync false
                }

                val cveId = request.vulnerability?.vulnerabilityId ?: "Unknown CVE"
                val assetName = request.vulnerability?.asset?.name ?: "Unknown Asset"
                val subject = "Exception Expiring Soon: $cveId on $assetName"

                val htmlContent = generateExpirationReminderEmail(request)
                val future = emailService.sendHtmlEmail(requesterEmail, subject, htmlContent)
                val sent = future.get()

                if (sent) {
                    logger.info("Sent expiration reminder to {} for requestId={}", requesterEmail, request.id)
                } else {
                    logger.warn("Failed to send expiration reminder to {} for requestId={}", requesterEmail, request.id)
                }

                return@supplyAsync sent

            } catch (e: Exception) {
                logger.error("Failed to send expiration reminder for requestId={}", request.id, e)
                return@supplyAsync false
            }
        }
    }

    /**
     * Generate HTML email for new exception request (to ADMIN/SECCHAMPION).
     *
     * Template includes:
     * - Vulnerability details (CVE, asset, severity)
     * - Requester information
     * - Request reason (truncated to 200 chars)
     * - Link to approval dashboard
     *
     * @param request The exception request
     * @param recipientName Name of the recipient (admin user)
     * @return HTML email content
     */
    private fun generateNewRequestEmail(request: VulnerabilityExceptionRequest, recipientName: String): String {
        val cveId = request.vulnerability?.vulnerabilityId ?: "Unknown CVE"
        val assetName = request.vulnerability?.asset?.name ?: "Unknown Asset"
        val assetIp = request.vulnerability?.asset?.ip ?: "N/A"
        val requesterName = request.requestedByUsername
        val reasonSummary = if (request.reason.length > 200) {
            request.reason.substring(0, 200) + "..."
        } else {
            request.reason
        }
        val submittedDate = request.createdAt?.format(DATE_FORMATTER) ?: "Unknown"
        val expirationDate = request.expirationDate.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))

        return """
<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8">
    <style>
        body { font-family: Arial, sans-serif; line-height: 1.6; color: #333; }
        .container { max-width: 600px; margin: 0 auto; padding: 20px; }
        .header { background-color: #dc3545; color: white; padding: 20px; text-align: center; border-radius: 5px 5px 0 0; }
        .content { background-color: #f8f9fa; padding: 20px; border: 1px solid #dee2e6; }
        .detail-row { margin-bottom: 15px; }
        .label { font-weight: bold; color: #495057; }
        .value { color: #212529; }
        .button { display: inline-block; padding: 12px 24px; background-color: #007bff; color: white; text-decoration: none; border-radius: 4px; margin-top: 20px; }
        .footer { margin-top: 20px; font-size: 12px; color: #6c757d; text-align: center; }
    </style>
</head>
<body>
    <div class="container">
        <div class="header">
            <h2>New Exception Request Requires Review</h2>
        </div>
        <div class="content">
            <p>Hello $recipientName,</p>

            <p>A new vulnerability exception request has been submitted and requires your review.</p>

            <div class="detail-row">
                <span class="label">CVE ID:</span> <code>$cveId</code>
            </div>
            <div class="detail-row">
                <span class="label">Asset:</span> <span class="value">$assetName ($assetIp)</span>
            </div>
            <div class="detail-row">
                <span class="label">Requested By:</span> <span class="value">$requesterName</span>
            </div>
            <div class="detail-row">
                <span class="label">Submitted:</span> <span class="value">$submittedDate</span>
            </div>
            <div class="detail-row">
                <span class="label">Requested Expiration:</span> <span class="value">$expirationDate</span>
            </div>
            <div class="detail-row">
                <span class="label">Reason:</span>
                <p style="background-color: white; padding: 10px; border-left: 3px solid #007bff; margin-top: 5px;">
                    $reasonSummary
                </p>
            </div>

            <p style="margin-top: 20px;">
                <a href="$DASHBOARD_URL" class="button">Review Request</a>
            </p>
        </div>
        <div class="footer">
            <p>This is an automated notification from SecMan. Please do not reply to this email.</p>
        </div>
    </div>
</body>
</html>
        """.trimIndent()
    }

    /**
     * Generate HTML email for approved exception request (to requester).
     *
     * Template includes:
     * - Vulnerability details
     * - Reviewer information
     * - Review comment (if provided)
     * - Expiration date
     * - Next steps
     *
     * @param request The approved exception request
     * @return HTML email content
     */
    private fun generateApprovalEmail(request: VulnerabilityExceptionRequest): String {
        val cveId = request.vulnerability?.vulnerabilityId ?: "Unknown CVE"
        val assetName = request.vulnerability?.asset?.name ?: "Unknown Asset"
        val reviewerName = request.reviewedByUsername ?: "Unknown Reviewer"
        val reviewDate = request.reviewDate?.format(DATE_FORMATTER) ?: "Unknown"
        val reviewComment = request.reviewComment ?: "(No comment provided)"
        val expirationDate = request.expirationDate.format(DateTimeFormatter.ofPattern("MMMM d, yyyy"))

        return """
<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8">
    <style>
        body { font-family: Arial, sans-serif; line-height: 1.6; color: #333; }
        .container { max-width: 600px; margin: 0 auto; padding: 20px; }
        .header { background-color: #28a745; color: white; padding: 20px; text-align: center; border-radius: 5px 5px 0 0; }
        .content { background-color: #f8f9fa; padding: 20px; border: 1px solid #dee2e6; }
        .detail-row { margin-bottom: 15px; }
        .label { font-weight: bold; color: #495057; }
        .value { color: #212529; }
        .success-icon { font-size: 48px; text-align: center; margin: 20px 0; }
        .footer { margin-top: 20px; font-size: 12px; color: #6c757d; text-align: center; }
        .warning { background-color: #fff3cd; border-left: 3px solid #ffc107; padding: 10px; margin-top: 20px; }
    </style>
</head>
<body>
    <div class="container">
        <div class="header">
            <div class="success-icon">✓</div>
            <h2>Exception Request Approved</h2>
        </div>
        <div class="content">
            <p>Good news! Your vulnerability exception request has been approved.</p>

            <div class="detail-row">
                <span class="label">CVE ID:</span> <code>$cveId</code>
            </div>
            <div class="detail-row">
                <span class="label">Asset:</span> <span class="value">$assetName</span>
            </div>
            <div class="detail-row">
                <span class="label">Reviewed By:</span> <span class="value">$reviewerName</span>
            </div>
            <div class="detail-row">
                <span class="label">Review Date:</span> <span class="value">$reviewDate</span>
            </div>
            <div class="detail-row">
                <span class="label">Expiration Date:</span> <span class="value">$expirationDate</span>
            </div>

            <div class="detail-row">
                <span class="label">Reviewer Comment:</span>
                <p style="background-color: white; padding: 10px; border-left: 3px solid #28a745; margin-top: 5px;">
                    $reviewComment
                </p>
            </div>

            <div class="warning">
                <strong>Important:</strong> This exception will expire on $expirationDate. You will receive a reminder 7 days before expiration. After expiration, this vulnerability will again be reported as active.
            </div>

            <p style="margin-top: 20px;"><strong>Next Steps:</strong></p>
            <ul>
                <li>The vulnerability will no longer appear in active vulnerability reports</li>
                <li>Monitor for expiration and plan remediation or renewal</li>
                <li>Maintain documentation of this approved exception</li>
            </ul>
        </div>
        <div class="footer">
            <p>This is an automated notification from SecMan. Please do not reply to this email.</p>
        </div>
    </div>
</body>
</html>
        """.trimIndent()
    }

    /**
     * Generate HTML email for rejected exception request (to requester).
     *
     * Template includes:
     * - Vulnerability details
     * - Reviewer information
     * - Review comment (required, explains rejection reason)
     * - Next steps (remediate or resubmit)
     *
     * @param request The rejected exception request
     * @return HTML email content
     */
    private fun generateRejectionEmail(request: VulnerabilityExceptionRequest): String {
        val cveId = request.vulnerability?.vulnerabilityId ?: "Unknown CVE"
        val assetName = request.vulnerability?.asset?.name ?: "Unknown Asset"
        val reviewerName = request.reviewedByUsername ?: "Unknown Reviewer"
        val reviewDate = request.reviewDate?.format(DATE_FORMATTER) ?: "Unknown"
        val reviewComment = request.reviewComment ?: "(No comment provided)"

        return """
<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8">
    <style>
        body { font-family: Arial, sans-serif; line-height: 1.6; color: #333; }
        .container { max-width: 600px; margin: 0 auto; padding: 20px; }
        .header { background-color: #dc3545; color: white; padding: 20px; text-align: center; border-radius: 5px 5px 0 0; }
        .content { background-color: #f8f9fa; padding: 20px; border: 1px solid #dee2e6; }
        .detail-row { margin-bottom: 15px; }
        .label { font-weight: bold; color: #495057; }
        .value { color: #212529; }
        .footer { margin-top: 20px; font-size: 12px; color: #6c757d; text-align: center; }
        .info { background-color: #d1ecf1; border-left: 3px solid #0c5460; padding: 10px; margin-top: 20px; }
    </style>
</head>
<body>
    <div class="container">
        <div class="header">
            <h2>Exception Request Rejected</h2>
        </div>
        <div class="content">
            <p>Your vulnerability exception request has been reviewed and rejected.</p>

            <div class="detail-row">
                <span class="label">CVE ID:</span> <code>$cveId</code>
            </div>
            <div class="detail-row">
                <span class="label">Asset:</span> <span class="value">$assetName</span>
            </div>
            <div class="detail-row">
                <span class="label">Reviewed By:</span> <span class="value">$reviewerName</span>
            </div>
            <div class="detail-row">
                <span class="label">Review Date:</span> <span class="value">$reviewDate</span>
            </div>

            <div class="detail-row">
                <span class="label">Rejection Reason:</span>
                <p style="background-color: white; padding: 10px; border-left: 3px solid #dc3545; margin-top: 5px;">
                    $reviewComment
                </p>
            </div>

            <div class="info">
                <strong>Next Steps:</strong>
                <ul>
                    <li><strong>Remediate the vulnerability:</strong> Address the security issue according to standard remediation procedures</li>
                    <li><strong>Resubmit with additional justification:</strong> If you believe this exception is warranted, you may submit a new request with more detailed reasoning</li>
                    <li><strong>Contact the reviewer:</strong> Reach out to $reviewerName for clarification on the rejection</li>
                </ul>
            </div>
        </div>
        <div class="footer">
            <p>This is an automated notification from SecMan. Please do not reply to this email.</p>
        </div>
    </div>
</body>
</html>
        """.trimIndent()
    }

    /**
     * Generate HTML email for expiration reminder (to requester).
     *
     * Template includes:
     * - Vulnerability details
     * - Expiration date (within 7 days)
     * - Renewal instructions
     *
     * @param request The exception request expiring soon
     * @return HTML email content
     */
    private fun generateExpirationReminderEmail(request: VulnerabilityExceptionRequest): String {
        val cveId = request.vulnerability?.vulnerabilityId ?: "Unknown CVE"
        val assetName = request.vulnerability?.asset?.name ?: "Unknown Asset"
        val expirationDate = request.expirationDate.format(DateTimeFormatter.ofPattern("MMMM d, yyyy"))

        return """
<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8">
    <style>
        body { font-family: Arial, sans-serif; line-height: 1.6; color: #333; }
        .container { max-width: 600px; margin: 0 auto; padding: 20px; }
        .header { background-color: #ffc107; color: #212529; padding: 20px; text-align: center; border-radius: 5px 5px 0 0; }
        .content { background-color: #f8f9fa; padding: 20px; border: 1px solid #dee2e6; }
        .detail-row { margin-bottom: 15px; }
        .label { font-weight: bold; color: #495057; }
        .value { color: #212529; }
        .warning-icon { font-size: 48px; text-align: center; margin: 20px 0; }
        .footer { margin-top: 20px; font-size: 12px; color: #6c757d; text-align: center; }
        .action { background-color: #fff3cd; border-left: 3px solid #ffc107; padding: 15px; margin-top: 20px; }
    </style>
</head>
<body>
    <div class="container">
        <div class="header">
            <div class="warning-icon">⚠️</div>
            <h2>Exception Expiring Soon</h2>
        </div>
        <div class="content">
            <p>This is a reminder that your vulnerability exception will expire within 7 days.</p>

            <div class="detail-row">
                <span class="label">CVE ID:</span> <code>$cveId</code>
            </div>
            <div class="detail-row">
                <span class="label">Asset:</span> <span class="value">$assetName</span>
            </div>
            <div class="detail-row">
                <span class="label">Expiration Date:</span> <span class="value" style="color: #dc3545; font-weight: bold;">$expirationDate</span>
            </div>

            <div class="action">
                <strong>Action Required:</strong>
                <p>Please take one of the following actions before the expiration date:</p>
                <ul>
                    <li><strong>Remediate the vulnerability:</strong> If possible, apply patches or mitigations to resolve the security issue</li>
                    <li><strong>Request renewal:</strong> If the exception is still needed, submit a new exception request with updated justification</li>
                    <li><strong>Accept expiration:</strong> If no action is taken, this vulnerability will reappear in active vulnerability reports after $expirationDate</li>
                </ul>
            </div>

            <p style="margin-top: 20px; font-size: 14px; color: #6c757d;">
                <em>You are receiving this reminder 7 days before expiration to give you time to plan remediation or renewal.</em>
            </p>
        </div>
        <div class="footer">
            <p>This is an automated notification from SecMan. Please do not reply to this email.</p>
        </div>
    </div>
</body>
</html>
        """.trimIndent()
    }
}
