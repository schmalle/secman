package com.secman.service

import com.secman.domain.ExceptionRequestStatus
import com.secman.repository.VulnerabilityExceptionRequestRepository
import jakarta.inject.Inject
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.LocalDateTime

/**
 * Service for calculating statistics and metrics for vulnerability exception requests.
 *
 * **Metrics Provided**:
 * - Approval Rate: (APPROVED count / (APPROVED + REJECTED)) * 100
 * - Average Approval Time: MEDIAN time from creation to review (in hours)
 * - Requests by Status: Count for each status (PENDING, APPROVED, REJECTED, EXPIRED, CANCELLED)
 * - Top Requesters: Most frequent requesters with count
 * - Top CVEs: Most frequently requested CVEs with count
 *
 * **Date Filtering**:
 * - All methods support optional date range parameter
 * - Date ranges: 7days, 30days, 90days, alltime (default: 30days)
 *
 * **Important**: Average approval time uses MEDIAN (not mean) per spec Assumption 8
 * to avoid outliers skewing the metric.
 *
 * Feature: 031-vuln-exception-approval
 * User Story 8: Analytics & Reporting (P3)
 * Phase 11: Analytics & Reporting
 * Reference: spec.md acceptance scenarios US8-1, US8-2
 */
@Singleton
open class ExceptionRequestStatisticsService(
    @Inject private val requestRepository: VulnerabilityExceptionRequestRepository
) {
    private val logger = LoggerFactory.getLogger(ExceptionRequestStatisticsService::class.java)

    /**
     * Calculate approval rate percentage.
     *
     * Formula: (APPROVED count / (APPROVED + REJECTED)) * 100
     *
     * If no reviewed requests exist, returns null.
     *
     * @param dateRange Optional date range (7days, 30days, 90days, alltime)
     * @return Approval rate percentage (0.0-100.0) or null if no data
     */
    open fun getApprovalRate(dateRange: String? = "30days"): Double? {
        val since = calculateDateRangeStart(dateRange)

        val approvedCount = if (since != null) {
            requestRepository.countByStatusAndCreatedAtAfter(ExceptionRequestStatus.APPROVED, since)
        } else {
            requestRepository.countByStatus(ExceptionRequestStatus.APPROVED)
        }

        val rejectedCount = if (since != null) {
            requestRepository.countByStatusAndCreatedAtAfter(ExceptionRequestStatus.REJECTED, since)
        } else {
            requestRepository.countByStatus(ExceptionRequestStatus.REJECTED)
        }

        val totalReviewed = approvedCount + rejectedCount

        if (totalReviewed == 0L) {
            logger.debug("No reviewed requests found for date range: {}", dateRange)
            return null
        }

        val rate = (approvedCount.toDouble() / totalReviewed.toDouble()) * 100.0
        logger.debug("Approval rate for {}: {:.2f}% ({} approved, {} rejected)", dateRange, rate, approvedCount, rejectedCount)

        return rate
    }

    /**
     * Calculate average (median) approval time in hours.
     *
     * Calculates time from created_at to review_date for APPROVED requests only.
     * Uses MEDIAN (not mean) per spec Assumption 8 to avoid outlier skew.
     *
     * If no approved requests exist, returns null.
     *
     * @param dateRange Optional date range (7days, 30days, 90days, alltime)
     * @return Median approval time in hours or null if no data
     */
    open fun getAverageApprovalTime(dateRange: String? = "30days"): Double? {
        val since = calculateDateRangeStart(dateRange)

        // Get all approved requests in date range
        val approvedRequests = if (since != null) {
            requestRepository.findByStatusAndCreatedAtAfter(ExceptionRequestStatus.APPROVED, since)
        } else {
            requestRepository.findByStatus(ExceptionRequestStatus.APPROVED, io.micronaut.data.model.Pageable.UNPAGED)
                .content
        }

        if (approvedRequests.isEmpty()) {
            logger.debug("No approved requests found for date range: {}", dateRange)
            return null
        }

        // Calculate approval times in hours
        val approvalTimes = approvedRequests.mapNotNull { request ->
            val createdAt = request.createdAt
            val reviewDate = request.reviewDate

            if (createdAt != null && reviewDate != null) {
                val duration = Duration.between(createdAt, reviewDate)
                duration.toHours().toDouble() + (duration.toMinutesPart() / 60.0)
            } else {
                null
            }
        }.sorted()

        if (approvalTimes.isEmpty()) {
            return null
        }

        // Calculate median
        val median = if (approvalTimes.size % 2 == 0) {
            // Even number of elements - average of middle two
            val mid1 = approvalTimes[approvalTimes.size / 2 - 1]
            val mid2 = approvalTimes[approvalTimes.size / 2]
            (mid1 + mid2) / 2.0
        } else {
            // Odd number of elements - middle element
            approvalTimes[approvalTimes.size / 2]
        }

        logger.debug("Median approval time for {}: {:.2f} hours (n={})", dateRange, median, approvalTimes.size)

        return median
    }

    /**
     * Get count of requests by status.
     *
     * Returns a map of status → count for all statuses.
     *
     * @param dateRange Optional date range (7days, 30days, 90days, alltime)
     * @return Map of status to count
     */
    open fun getRequestsByStatus(dateRange: String? = "30days"): Map<ExceptionRequestStatus, Long> {
        val since = calculateDateRangeStart(dateRange)

        val statusCounts = mutableMapOf<ExceptionRequestStatus, Long>()

        for (status in ExceptionRequestStatus.values()) {
            val count = if (since != null) {
                requestRepository.countByStatusAndCreatedAtAfter(status, since)
            } else {
                requestRepository.countByStatus(status)
            }
            statusCounts[status] = count
        }

        logger.debug("Requests by status for {}: {}", dateRange, statusCounts)

        return statusCounts
    }

    /**
     * Get top requesters by request count.
     *
     * Returns list of usernames with their request counts, sorted descending.
     *
     * @param limit Maximum number of requesters to return (default: 10)
     * @param dateRange Optional date range (7days, 30days, 90days, alltime)
     * @return List of requester username to count pairs
     */
    open fun getTopRequesters(limit: Int = 10, dateRange: String? = "30days"): List<Pair<String, Long>> {
        val since = calculateDateRangeStart(dateRange)

        // Get all requests in date range
        val requests = if (since != null) {
            requestRepository.findByCreatedAtAfter(since)
        } else {
            requestRepository.findAll().toList()
        }

        // Group by requester username and count
        val requesterCounts = requests
            .groupingBy { it.requestedByUsername }
            .eachCount()
            .map { (username, count) -> username to count.toLong() }
            .sortedByDescending { it.second }
            .take(limit)

        logger.debug("Top {} requesters for {}: {}", limit, dateRange, requesterCounts.take(3))

        return requesterCounts
    }

    /**
     * Get top CVEs by request count.
     *
     * Returns list of CVE IDs with their request counts, sorted descending.
     *
     * @param limit Maximum number of CVEs to return (default: 10)
     * @param dateRange Optional date range (7days, 30days, 90days, alltime)
     * @return List of CVE ID to count pairs
     */
    open fun getTopCVEs(limit: Int = 10, dateRange: String? = "30days"): List<Pair<String, Long>> {
        val since = calculateDateRangeStart(dateRange)

        // Get all requests in date range
        val requests = if (since != null) {
            requestRepository.findByCreatedAtAfter(since)
        } else {
            requestRepository.findAll().toList()
        }

        // Group by CVE ID and count
        val cveCounts = requests
            .mapNotNull { it.vulnerability?.vulnerabilityId }
            .groupingBy { it }
            .eachCount()
            .map { (cveId, count) -> cveId to count.toLong() }
            .sortedByDescending { it.second }
            .take(limit)

        logger.debug("Top {} CVEs for {}: {}", limit, dateRange, cveCounts.take(3))

        return cveCounts
    }

    /**
     * Calculate date range start based on range string.
     *
     * @param dateRange Range string: "7days", "30days", "90days", "alltime", null
     * @return LocalDateTime for range start, or null for "alltime"/null
     */
    private fun calculateDateRangeStart(dateRange: String?): LocalDateTime? {
        return when (dateRange?.lowercase()) {
            "7days" -> LocalDateTime.now().minusDays(7)
            "30days" -> LocalDateTime.now().minusDays(30)
            "90days" -> LocalDateTime.now().minusDays(90)
            "alltime", null -> null
            else -> {
                logger.warn("Unknown date range: {}, defaulting to 30days", dateRange)
                LocalDateTime.now().minusDays(30)
            }
        }
    }
}
