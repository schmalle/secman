package com.secman.service

import com.secman.config.AppConfig
import com.secman.domain.AdminSummaryLog
import com.secman.domain.ExecutionStatus
import com.secman.domain.User
import com.secman.repository.AdminSummaryLogRepository
import com.secman.repository.AssetRepository
import com.secman.repository.UserRepository
import com.secman.repository.VulnerabilityRepository
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Service for sending admin summary emails with system statistics.
 * Feature: 070-admin-summary-email
 * Enhanced: 069-enhanced-admin-summary (link, top products, top servers)
 */
@Singleton
class AdminSummaryService(
    private val userRepository: UserRepository,
    private val vulnerabilityRepository: VulnerabilityRepository,
    private val assetRepository: AssetRepository,
    private val adminSummaryLogRepository: AdminSummaryLogRepository,
    private val emailService: EmailService,
    private val appConfig: AppConfig
) {
    private val logger = LoggerFactory.getLogger(AdminSummaryService::class.java)

    /**
     * Summary entry for a product's vulnerability count.
     * Feature: 069-enhanced-admin-summary
     */
    data class ProductSummary(
        val name: String,
        val vulnerabilityCount: Long
    )

    /**
     * Summary entry for a server's vulnerability count.
     * Feature: 069-enhanced-admin-summary
     */
    data class ServerSummary(
        val name: String,
        val vulnerabilityCount: Long
    )

    /**
     * System statistics data class
     */
    data class SystemStatistics(
        val userCount: Long,
        val vulnerabilityCount: Long,
        val assetCount: Long,
        val vulnerabilityStatisticsUrl: String = "",
        val topProducts: List<ProductSummary> = emptyList(),
        val topServers: List<ServerSummary> = emptyList()
    )

    /**
     * Result of admin summary email execution
     */
    data class AdminSummaryResult(
        val recipientCount: Int,
        val emailsSent: Int,
        val emailsFailed: Int,
        val status: ExecutionStatus,
        val recipients: List<String>,
        val failedRecipients: List<String>
    )

    /**
     * Get current system statistics (user count, vulnerability count, asset count,
     * vulnerability statistics URL, top 10 products, top 10 servers)
     */
    fun getSystemStatistics(): SystemStatistics {
        val userCount = userRepository.count()
        val vulnerabilityCount = vulnerabilityRepository.count()
        val assetCount = assetRepository.count()

        val baseUrl = appConfig.backend.baseUrl.trimEnd('/')
        val vulnerabilityStatisticsUrl = "$baseUrl/vulnerability-statistics"

        val topProducts = getTopProductsAdmin()
        val topServers = getTopServersAdmin()

        logger.debug("System statistics: users={}, vulnerabilities={}, assets={}, topProducts={}, topServers={}",
            userCount, vulnerabilityCount, assetCount, topProducts.size, topServers.size)

        return SystemStatistics(
            userCount = userCount,
            vulnerabilityCount = vulnerabilityCount,
            assetCount = assetCount,
            vulnerabilityStatisticsUrl = vulnerabilityStatisticsUrl,
            topProducts = topProducts,
            topServers = topServers
        )
    }

    /**
     * Get top 10 most vulnerable products (admin-level, unfiltered).
     * Feature: 069-enhanced-admin-summary
     */
    private fun getTopProductsAdmin(): List<ProductSummary> {
        return try {
            vulnerabilityRepository.findMostVulnerableProductsForAll().map { row ->
                ProductSummary(
                    name = row.product ?: "",
                    vulnerabilityCount = row.vulnerabilityCount?.toLong() ?: 0L
                )
            }
        } catch (e: Exception) {
            logger.error("Failed to fetch top products: {}", e.message)
            emptyList()
        }
    }

    /**
     * Get top 10 most affected servers (admin-level, unfiltered).
     * Feature: 069-enhanced-admin-summary
     */
    private fun getTopServersAdmin(): List<ServerSummary> {
        return try {
            vulnerabilityRepository.findTopAssetsByVulnerabilitiesForAll()
                .take(10)
                .map { row ->
                    ServerSummary(
                        name = row["assetName"] as? String ?: "",
                        vulnerabilityCount = (row["totalVulnerabilityCount"] as? Number)?.toLong() ?: 0L
                    )
                }
        } catch (e: Exception) {
            logger.error("Failed to fetch top servers: {}", e.message)
            emptyList()
        }
    }

    /**
     * Get all ADMIN users with valid email addresses
     */
    fun getAdminRecipients(): List<User> {
        val adminUsers = userRepository.findByRolesContaining(User.Role.ADMIN)
            .filter { it.email.isNotBlank() }

        logger.debug("Found {} ADMIN users with valid email", adminUsers.size)
        return adminUsers
    }

    /**
     * Send summary email to all ADMIN users
     *
     * @param dryRun If true, skip actual email sending but return planned recipients
     * @param verbose If true, log detailed per-recipient information
     * @return Result containing counts and recipient details
     */
    fun sendSummaryEmail(dryRun: Boolean = false, verbose: Boolean = false): AdminSummaryResult {
        val statistics = getSystemStatistics()
        val recipients = getAdminRecipients()

        if (recipients.isEmpty()) {
            logger.warn("No ADMIN users with valid email found")
            val result = AdminSummaryResult(
                recipientCount = 0,
                emailsSent = 0,
                emailsFailed = 0,
                status = ExecutionStatus.FAILURE,
                recipients = emptyList(),
                failedRecipients = emptyList()
            )
            logExecution(statistics, result, dryRun)
            return result
        }

        if (dryRun) {
            logger.info("Dry run mode - would send to {} recipients", recipients.size)
            val result = AdminSummaryResult(
                recipientCount = recipients.size,
                emailsSent = 0,
                emailsFailed = 0,
                status = ExecutionStatus.DRY_RUN,
                recipients = recipients.map { it.email },
                failedRecipients = emptyList()
            )
            logExecution(statistics, result, dryRun)
            return result
        }

        // Render email templates
        val executionDate = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault())
            .format(Instant.now())

        val htmlContent = renderHtmlTemplate(statistics, executionDate)
        val textContent = renderTextTemplate(statistics, executionDate)

        val sentRecipients = mutableListOf<String>()
        val failedRecipients = mutableListOf<String>()

        recipients.forEach { user ->
            try {
                if (verbose) {
                    logger.info("Sending admin summary email to {}", user.email)
                }

                val success = emailService.sendEmail(
                    to = user.email,
                    subject = "SecMan Admin Summary",
                    textContent = textContent,
                    htmlContent = htmlContent
                ).get()

                if (success) {
                    sentRecipients.add(user.email)
                    if (verbose) {
                        logger.info("Successfully sent to {}", user.email)
                    }
                } else {
                    failedRecipients.add(user.email)
                    logger.warn("Failed to send email to {}", user.email)
                }
            } catch (e: Exception) {
                failedRecipients.add(user.email)
                logger.error("Error sending email to {}: {}", user.email, e.message)
            }
        }

        val status = when {
            failedRecipients.isEmpty() -> ExecutionStatus.SUCCESS
            sentRecipients.isEmpty() -> ExecutionStatus.FAILURE
            else -> ExecutionStatus.PARTIAL_FAILURE
        }

        val result = AdminSummaryResult(
            recipientCount = recipients.size,
            emailsSent = sentRecipients.size,
            emailsFailed = failedRecipients.size,
            status = status,
            recipients = sentRecipients,
            failedRecipients = failedRecipients
        )

        logExecution(statistics, result, dryRun)
        return result
    }

    /**
     * Log execution to database for audit purposes
     */
    fun logExecution(statistics: SystemStatistics, result: AdminSummaryResult, dryRun: Boolean) {
        try {
            val log = AdminSummaryLog(
                executedAt = Instant.now(),
                recipientCount = result.recipientCount,
                userCount = statistics.userCount,
                vulnerabilityCount = statistics.vulnerabilityCount,
                assetCount = statistics.assetCount,
                emailsSent = result.emailsSent,
                emailsFailed = result.emailsFailed,
                status = result.status,
                dryRun = dryRun
            )
            adminSummaryLogRepository.save(log)
            logger.debug("Logged admin summary execution: status={}", result.status)
        } catch (e: Exception) {
            logger.error("Failed to log admin summary execution: {}", e.message)
        }
    }

    /**
     * Render HTML email template with statistics
     */
    private fun renderHtmlTemplate(statistics: SystemStatistics, executionDate: String): String {
        return try {
            val templateStream = javaClass.getResourceAsStream("/email-templates/admin-summary.html")
                ?: throw IllegalStateException("HTML template not found")

            templateStream.bufferedReader().use { reader ->
                reader.readText()
                    .replace("\${executionDate}", executionDate)
                    .replace("\${userCount}", statistics.userCount.toString())
                    .replace("\${vulnerabilityCount}", statistics.vulnerabilityCount.toString())
                    .replace("\${assetCount}", statistics.assetCount.toString())
                    .replace("\${vulnerabilityStatisticsUrl}", statistics.vulnerabilityStatisticsUrl)
                    .replace("\${topProductsHtml}", renderTopProductsHtml(statistics.topProducts))
                    .replace("\${topServersHtml}", renderTopServersHtml(statistics.topServers))
            }
        } catch (e: Exception) {
            logger.error("Failed to render HTML template: {}", e.message)
            // Fallback simple HTML
            """
            <html>
            <body>
                <h1>SecMan Admin Summary</h1>
                <p>Generated on: $executionDate</p>
                <ul>
                    <li>Total Users: ${statistics.userCount}</li>
                    <li>Total Vulnerabilities: ${statistics.vulnerabilityCount}</li>
                    <li>Total Assets: ${statistics.assetCount}</li>
                </ul>
            </body>
            </html>
            """.trimIndent()
        }
    }

    /**
     * Render plain text email template with statistics
     */
    private fun renderTextTemplate(statistics: SystemStatistics, executionDate: String): String {
        return try {
            val templateStream = javaClass.getResourceAsStream("/email-templates/admin-summary.txt")
                ?: throw IllegalStateException("Text template not found")

            templateStream.bufferedReader().use { reader ->
                reader.readText()
                    .replace("\${executionDate}", executionDate)
                    .replace("\${userCount}", statistics.userCount.toString())
                    .replace("\${vulnerabilityCount}", statistics.vulnerabilityCount.toString())
                    .replace("\${assetCount}", statistics.assetCount.toString())
                    .replace("\${vulnerabilityStatisticsUrl}", statistics.vulnerabilityStatisticsUrl)
                    .replace("\${topProductsText}", renderTopProductsText(statistics.topProducts))
                    .replace("\${topServersText}", renderTopServersText(statistics.topServers))
            }
        } catch (e: Exception) {
            logger.error("Failed to render text template: {}", e.message)
            // Fallback simple text
            """
            SecMan Admin Summary
            ====================
            Generated on: $executionDate

            Total Users: ${statistics.userCount}
            Total Vulnerabilities: ${statistics.vulnerabilityCount}
            Total Assets: ${statistics.assetCount}
            """.trimIndent()
        }
    }

    /**
     * Render top products as HTML table rows.
     * Feature: 069-enhanced-admin-summary
     */
    private fun renderTopProductsHtml(products: List<ProductSummary>): String {
        if (products.isEmpty()) {
            return """<p style="color: #6c757d; font-style: italic;">No vulnerability data available</p>"""
        }
        val rows = products.joinToString("\n") { product ->
            """            <tr>
                <td style="padding: 8px 12px; border-bottom: 1px solid #e9ecef;">${product.name}</td>
                <td style="padding: 8px 12px; border-bottom: 1px solid #e9ecef; text-align: right; font-weight: bold; color: #0d6efd;">${product.vulnerabilityCount}</td>
            </tr>"""
        }
        return """<table style="width: 100%; border-collapse: collapse;">
            <tr style="background-color: #e9ecef;">
                <th style="padding: 8px 12px; text-align: left;">Product</th>
                <th style="padding: 8px 12px; text-align: right;">Vulnerabilities</th>
            </tr>
$rows
        </table>"""
    }

    /**
     * Render top servers as HTML table rows.
     * Feature: 069-enhanced-admin-summary
     */
    private fun renderTopServersHtml(servers: List<ServerSummary>): String {
        if (servers.isEmpty()) {
            return """<p style="color: #6c757d; font-style: italic;">No vulnerability data available</p>"""
        }
        val rows = servers.joinToString("\n") { server ->
            """            <tr>
                <td style="padding: 8px 12px; border-bottom: 1px solid #e9ecef;">${server.name}</td>
                <td style="padding: 8px 12px; border-bottom: 1px solid #e9ecef; text-align: right; font-weight: bold; color: #0d6efd;">${server.vulnerabilityCount}</td>
            </tr>"""
        }
        return """<table style="width: 100%; border-collapse: collapse;">
            <tr style="background-color: #e9ecef;">
                <th style="padding: 8px 12px; text-align: left;">Server</th>
                <th style="padding: 8px 12px; text-align: right;">Vulnerabilities</th>
            </tr>
$rows
        </table>"""
    }

    /**
     * Render top products as plain text lines.
     * Feature: 069-enhanced-admin-summary
     */
    private fun renderTopProductsText(products: List<ProductSummary>): String {
        if (products.isEmpty()) {
            return "No vulnerability data available"
        }
        val maxNameLen = products.maxOf { it.name.length }.coerceAtLeast(7)
        return products.joinToString("\n") { product ->
            "   ${product.name.padEnd(maxNameLen)}  ${product.vulnerabilityCount}"
        }
    }

    /**
     * Render top servers as plain text lines.
     * Feature: 069-enhanced-admin-summary
     */
    private fun renderTopServersText(servers: List<ServerSummary>): String {
        if (servers.isEmpty()) {
            return "No vulnerability data available"
        }
        val maxNameLen = servers.maxOf { it.name.length }.coerceAtLeast(6)
        return servers.joinToString("\n") { server ->
            "   ${server.name.padEnd(maxNameLen)}  ${server.vulnerabilityCount}"
        }
    }
}
