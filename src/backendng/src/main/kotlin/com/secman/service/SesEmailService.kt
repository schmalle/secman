package com.secman.service

import com.secman.domain.EmailConfig
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.ses.SesClient
import software.amazon.awssdk.services.ses.model.*

/**
 * Amazon SES email sending service
 * Provides email delivery via AWS Simple Email Service
 */
@Singleton
class SesEmailService {
    private val logger = LoggerFactory.getLogger(SesEmailService::class.java)

    data class SendResult(
        val success: Boolean,
        val errorMessage: String? = null,
        val messageId: String? = null
    )

    /**
     * Send email via Amazon SES
     */
    fun sendEmail(
        config: EmailConfig,
        to: String,
        subject: String,
        htmlBody: String,
        plainTextBody: String
    ): SendResult {
        if (config.sesAccessKey.isNullOrBlank() || config.sesSecretKey.isNullOrBlank() || config.sesRegion.isNullOrBlank()) {
            return SendResult(
                success = false,
                errorMessage = "SES configuration is incomplete"
            )
        }

        try {
            val credentials = AwsBasicCredentials.create(config.sesAccessKey, config.sesSecretKey)
            val credentialsProvider = StaticCredentialsProvider.create(credentials)
            val region = Region.of(config.sesRegion)

            val sesClient = SesClient.builder()
                .region(region)
                .credentialsProvider(credentialsProvider)
                .build()

            sesClient.use { client ->
                val destination = Destination.builder()
                    .toAddresses(to)
                    .build()

                val bodyContent = Body.builder()
                    .html(Content.builder().data(htmlBody).charset("UTF-8").build())
                    .text(Content.builder().data(plainTextBody).charset("UTF-8").build())
                    .build()

                val messageContent = Message.builder()
                    .subject(Content.builder().data(sanitizeEmailHeader(subject)).charset("UTF-8").build())
                    .body(bodyContent)
                    .build()

                val sendEmailRequest = SendEmailRequest.builder()
                    .source("${config.fromName} <${config.fromEmail}>")
                    .destination(destination)
                    .message(messageContent)
                    .build()

                val response = client.sendEmail(sendEmailRequest)

                logger.info("Email sent successfully via SES to $to, MessageId: ${response.messageId()}")

                return SendResult(
                    success = true,
                    messageId = response.messageId()
                )
            }
        } catch (e: SesException) {
            val errorMsg = "SES error: ${e.awsErrorDetails().errorMessage()}"
            logger.error(errorMsg, e)
            return SendResult(success = false, errorMessage = errorMsg)
        } catch (e: Exception) {
            val errorMsg = "Failed to send email via SES: ${e.message}"
            logger.error(errorMsg, e)
            return SendResult(success = false, errorMessage = errorMsg)
        }
    }

    /**
     * Send test email via SES
     */
    fun sendTestEmail(config: EmailConfig, toAddress: String): SendResult {
        val htmlBody = """
            <html>
            <body>
                <h2>Test Email from Amazon SES</h2>
                <p>This is a test email from SecMan notification system using Amazon SES.</p>
                <p>If you received this, your SES configuration is working correctly.</p>
            </body>
            </html>
        """.trimIndent()

        val plainTextBody = """
            Test Email from Amazon SES

            This is a test email from SecMan notification system using Amazon SES.
            If you received this, your SES configuration is working correctly.
        """.trimIndent()

        return sendEmail(
            config = config,
            to = toAddress,
            subject = "SecMan Test Email (Amazon SES)",
            htmlBody = htmlBody,
            plainTextBody = plainTextBody
        )
    }

    /**
     * Sanitize email header to prevent header injection attacks (CWE-93)
     * Removes CRLF characters that could be used to inject additional headers
     */
    private fun sanitizeEmailHeader(value: String): String {
        return value.replace(Regex("[\r\n]"), "")
    }

    /**
     * Verify SES configuration by checking if the from email is verified
     */
    fun verifyConfiguration(config: EmailConfig): Pair<Boolean, String> {
        if (config.sesAccessKey.isNullOrBlank() || config.sesSecretKey.isNullOrBlank() || config.sesRegion.isNullOrBlank()) {
            return Pair(false, "SES configuration is incomplete")
        }

        try {
            val credentials = AwsBasicCredentials.create(config.sesAccessKey, config.sesSecretKey)
            val credentialsProvider = StaticCredentialsProvider.create(credentials)
            val region = Region.of(config.sesRegion)

            val sesClient = SesClient.builder()
                .region(region)
                .credentialsProvider(credentialsProvider)
                .build()

            sesClient.use { client ->
                val request = GetAccountSendingEnabledRequest.builder().build()
                val response = client.getAccountSendingEnabled(request)

                if (!response.enabled()) {
                    return Pair(false, "SES account sending is disabled")
                }

                return Pair(true, "SES configuration is valid")
            }
        } catch (e: SesException) {
            return Pair(false, "SES error: ${e.awsErrorDetails().errorMessage()}")
        } catch (e: Exception) {
            return Pair(false, "Configuration verification failed: ${e.message}")
        }
    }
}
