package com.secman.repository

import com.secman.domain.TestEmailAccount
import com.secman.domain.enums.EmailProvider
import com.secman.domain.enums.TestAccountStatus
import io.micronaut.data.annotation.Query
import io.micronaut.data.annotation.Repository
import io.micronaut.data.jpa.repository.JpaRepository
import java.time.LocalDateTime

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
     * Find accounts by multiple statuses
     */
    @Query("SELECT t FROM TestEmailAccount t WHERE t.status IN :statuses")
    fun findByStatusIn(statuses: List<TestAccountStatus>): List<TestEmailAccount>

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
     * Check if name exists (for unique validation)
     */
    fun existsByName(name: String): Boolean
}