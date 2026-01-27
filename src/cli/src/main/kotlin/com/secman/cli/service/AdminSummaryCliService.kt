package com.secman.cli.service

import com.secman.service.AdminSummaryService
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory

/**
 * CLI-specific service that bridges CLI command to backend AdminSummaryService.
 * Feature: 070-admin-summary-email
 */
@Singleton
class AdminSummaryCliService(
    private val adminSummaryService: AdminSummaryService
) {
    private val logger = LoggerFactory.getLogger(AdminSummaryCliService::class.java)

    /**
     * Execute admin summary email send
     *
     * @param dryRun If true, preview without sending emails
     * @param verbose If true, log detailed per-recipient information
     * @return Result from AdminSummaryService
     */
    fun execute(dryRun: Boolean, verbose: Boolean): AdminSummaryService.AdminSummaryResult {
        if (verbose) {
            logger.info("Starting admin summary email process (dry-run: {})", dryRun)
        }

        val result = adminSummaryService.sendSummaryEmail(dryRun, verbose)

        if (verbose) {
            logger.info("Admin summary complete: sent={}, failed={}, status={}",
                result.emailsSent, result.emailsFailed, result.status)
        }

        return result
    }

    /**
     * Get system statistics for display
     */
    fun getStatistics(): AdminSummaryService.SystemStatistics {
        return adminSummaryService.getSystemStatistics()
    }

    /**
     * Get list of ADMIN recipients
     */
    fun getRecipients(): List<String> {
        return adminSummaryService.getAdminRecipients().map { it.email }
    }
}
