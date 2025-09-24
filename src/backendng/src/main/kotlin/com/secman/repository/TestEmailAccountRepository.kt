package com.secman.repository

import com.secman.domain.TestEmailAccount
import com.secman.domain.enums.EmailProvider
import com.secman.domain.enums.TestAccountStatus
import io.micronaut.data.annotation.Query
import io.micronaut.data.annotation.Repository
import io.micronaut.data.jpa.repository.JpaRepository
import java.time.LocalDateTime
import java.util.*

/**
 * Repository for TestEmailAccount entities
 */
@Repository
interface TestEmailAccountRepository : JpaRepository<TestEmailAccount, Long> {

    /**
     * Find all test accounts by status
     */
    fun findByStatus(status: TestAccountStatus): List<TestEmailAccount>

    /**
     * Find all test accounts by provider
     */
    fun findByProvider(provider: EmailProvider): List<TestEmailAccount>

    /**
     * Find test accounts by status and provider
     */
    fun findByStatusAndProvider(status: TestAccountStatus, provider: EmailProvider): List<TestEmailAccount>

    /**
     * Find test account by email address
     */
    fun findByEmailAddress(emailAddress: String): Optional<TestEmailAccount>

    /**
     * Find all active test accounts
     */
    @Query("SELECT t FROM TestEmailAccount t WHERE t.status = 'ACTIVE'")
    fun findActiveAccounts(): List<TestEmailAccount>

    /**
     * Find all accounts that can be tested
     */
    @Query("SELECT t FROM TestEmailAccount t WHERE t.status IN ('ACTIVE')")
    fun findTestableAccounts(): List<TestEmailAccount>

    /**
     * Find accounts that need verification
     */
    @Query("SELECT t FROM TestEmailAccount t WHERE t.status IN ('VERIFICATION_PENDING', 'FAILED')")
    fun findAccountsNeedingVerification(): List<TestEmailAccount>

    /**
     * Find accounts last tested before a certain date
     */
    @Query("SELECT t FROM TestEmailAccount t WHERE t.lastTestedAt IS NULL OR t.lastTestedAt < :date")
    fun findAccountsNotTestedSince(date: LocalDateTime): List<TestEmailAccount>

    /**
     * Find accounts by name (case insensitive)
     */
    @Query("SELECT t FROM TestEmailAccount t WHERE LOWER(t.name) LIKE LOWER(CONCAT('%', :name, '%'))")
    fun findByNameContainingIgnoreCase(name: String): List<TestEmailAccount>

    /**
     * Count accounts by status
     */
    fun countByStatus(status: TestAccountStatus): Long

    /**
     * Count accounts by provider
     */
    fun countByProvider(provider: EmailProvider): Long

    /**
     * Find recently created accounts
     */
    @Query("SELECT t FROM TestEmailAccount t WHERE t.createdAt >= :since ORDER BY t.createdAt DESC")
    fun findRecentlyCreated(since: LocalDateTime): List<TestEmailAccount>

    /**
     * Find accounts with recent test activity
     */
    @Query("SELECT t FROM TestEmailAccount t WHERE t.lastTestedAt >= :since ORDER BY t.lastTestedAt DESC")
    fun findRecentlyTested(since: LocalDateTime): List<TestEmailAccount>

    /**
     * Find accounts by multiple statuses
     */
    @Query("SELECT t FROM TestEmailAccount t WHERE t.status IN :statuses")
    fun findByStatusIn(statuses: List<TestAccountStatus>): List<TestEmailAccount>

    /**
     * Find accounts with failed status and error details
     */
    @Query("SELECT t FROM TestEmailAccount t WHERE t.status = 'FAILED' AND t.lastTestResult IS NOT NULL")
    fun findFailedAccountsWithDetails(): List<TestEmailAccount>

    /**
     * Get account statistics
     */
    @Query("""
        SELECT new map(
            t.status as status,
            COUNT(t) as count
        )
        FROM TestEmailAccount t
        GROUP BY t.status
    """)
    fun getAccountStatsByStatus(): List<Map<String, Any>>

    /**
     * Get provider statistics
     */
    @Query("""
        SELECT new map(
            t.provider as provider,
            COUNT(t) as count
        )
        FROM TestEmailAccount t
        GROUP BY t.provider
    """)
    fun getAccountStatsByProvider(): List<Map<String, Any>>

    /**
     * Find accounts created between dates
     */
    @Query("SELECT t FROM TestEmailAccount t WHERE t.createdAt BETWEEN :startDate AND :endDate ORDER BY t.createdAt DESC")
    fun findCreatedBetween(startDate: LocalDateTime, endDate: LocalDateTime): List<TestEmailAccount>

    /**
     * Update account status by ID
     */
    @Query("UPDATE TestEmailAccount t SET t.status = :status, t.updatedAt = :updatedAt WHERE t.id = :id")
    fun updateStatus(id: Long, status: TestAccountStatus, updatedAt: LocalDateTime): Int

    /**
     * Update last test result
     */
    @Query("""
        UPDATE TestEmailAccount t
        SET t.lastTestResult = :testResult,
            t.lastTestedAt = :testedAt,
            t.status = :status,
            t.updatedAt = :updatedAt
        WHERE t.id = :id
    """)
    fun updateTestResult(
        id: Long,
        testResult: String?,
        testedAt: LocalDateTime,
        status: TestAccountStatus,
        updatedAt: LocalDateTime
    ): Int

    /**
     * Delete accounts older than specified date
     */
    @Query("DELETE FROM TestEmailAccount t WHERE t.createdAt < :beforeDate")
    fun deleteAccountsOlderThan(beforeDate: LocalDateTime): Int

    /**
     * Check if email address exists
     */
    fun existsByEmailAddress(emailAddress: String): Boolean

    /**
     * Check if name exists (for unique validation)
     */
    fun existsByName(name: String): Boolean

    /**
     * Find accounts ready for automatic testing
     */
    @Query("""
        SELECT t FROM TestEmailAccount t
        WHERE t.status = 'ACTIVE'
        AND (t.lastTestedAt IS NULL OR t.lastTestedAt < :testThreshold)
        ORDER BY t.lastTestedAt ASC NULLS FIRST
    """)
    fun findAccountsReadyForTesting(testThreshold: LocalDateTime): List<TestEmailAccount>
}