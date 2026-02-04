package com.secman.repository

import com.secman.domain.ExportJob
import com.secman.domain.ExportJobStatus
import com.secman.domain.ExportType
import io.micronaut.data.annotation.Query
import io.micronaut.data.annotation.Repository
import io.micronaut.data.jpa.repository.JpaRepository
import java.time.LocalDateTime
import java.util.Optional

/**
 * Repository for ExportJob entities
 * Feature: Vulnerability Export Performance Optimization - Background Job Pattern
 */
@Repository
interface ExportJobRepository : JpaRepository<ExportJob, String> {

    /**
     * Find job by ID and username (for authorization)
     */
    fun findByIdAndUsername(id: String, username: String): Optional<ExportJob>

    /**
     * Find all jobs for a user, ordered by creation time descending
     */
    fun findByUsernameOrderByCreatedAtDesc(username: String): List<ExportJob>

    /**
     * Find recent jobs for a user (last N)
     */
    @Query("SELECT e FROM ExportJob e WHERE e.username = :username ORDER BY e.createdAt DESC")
    fun findRecentByUsername(username: String, limit: Int = 10): List<ExportJob>

    /**
     * Find running jobs for a user (to prevent multiple concurrent exports)
     */
    fun findByUsernameAndStatusIn(username: String, statuses: List<ExportJobStatus>): List<ExportJob>

    /**
     * Find jobs older than a certain date (for cleanup)
     */
    fun findByCreatedAtBefore(cutoffDate: LocalDateTime): List<ExportJob>

    /**
     * Find completed jobs older than a certain date (for file cleanup)
     */
    fun findByStatusAndCompletedAtBefore(status: ExportJobStatus, cutoffDate: LocalDateTime): List<ExportJob>

    /**
     * Count running jobs for a user
     */
    fun countByUsernameAndStatusIn(username: String, statuses: List<ExportJobStatus>): Long

    /**
     * Count total running jobs (for global rate limiting)
     */
    fun countByStatusIn(statuses: List<ExportJobStatus>): Long

    /**
     * Find all jobs with a specific status
     */
    fun findByStatus(status: ExportJobStatus): List<ExportJob>
}
