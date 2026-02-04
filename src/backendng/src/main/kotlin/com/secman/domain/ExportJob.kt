package com.secman.domain

import jakarta.persistence.*
import java.time.LocalDateTime

/**
 * Entity for tracking background export jobs
 * Feature: Vulnerability Export Performance Optimization - Background Job Pattern
 *
 * Stores metadata about export jobs including status, progress, and file location.
 * Jobs are processed asynchronously and files are stored temporarily on disk.
 */
@Entity
@Table(name = "export_jobs")
class ExportJob(
    @Id
    @Column(length = 36)
    var id: String,

    @Column(nullable = false, length = 50)
    var username: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: ExportJobStatus = ExportJobStatus.PENDING,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    var exportType: ExportType = ExportType.VULNERABILITIES,

    @Column(nullable = false)
    var totalItems: Long = 0,

    @Column(nullable = false)
    var processedItems: Long = 0,

    @Column(length = 500)
    var filePath: String? = null,

    @Column(length = 255)
    var fileName: String? = null,

    @Column
    var fileSizeBytes: Long? = null,

    @Column(length = 1000)
    var errorMessage: String? = null,

    @Column(nullable = false)
    var createdAt: LocalDateTime = LocalDateTime.now(),

    @Column
    var startedAt: LocalDateTime? = null,

    @Column
    var completedAt: LocalDateTime? = null
) {
    /**
     * Calculate progress percentage (0-100)
     */
    fun getProgressPercent(): Int {
        if (totalItems == 0L) return 0
        return ((processedItems * 100) / totalItems).toInt().coerceIn(0, 100)
    }

    /**
     * Check if job can be downloaded
     */
    fun isDownloadable(): Boolean {
        return status == ExportJobStatus.COMPLETED && filePath != null
    }

    /**
     * Check if job is still running
     */
    fun isRunning(): Boolean {
        return status == ExportJobStatus.PENDING || status == ExportJobStatus.PROCESSING
    }
}

/**
 * Export job status enum
 */
enum class ExportJobStatus {
    PENDING,      // Job created, waiting to start
    PROCESSING,   // Job is running
    COMPLETED,    // Job finished successfully
    FAILED,       // Job failed with error
    CANCELLED,    // Job was cancelled
    EXPIRED       // Job file was cleaned up
}

/**
 * Export type enum (for future extensibility)
 */
enum class ExportType {
    VULNERABILITIES,
    ASSETS,
    REQUIREMENTS
}
