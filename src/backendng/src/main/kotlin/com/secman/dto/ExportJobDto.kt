package com.secman.dto

import com.secman.domain.ExportJob
import com.secman.domain.ExportJobStatus
import com.secman.domain.ExportType
import io.micronaut.serde.annotation.Serdeable
import java.time.LocalDateTime

/**
 * DTO for export job status response
 * Feature: Vulnerability Export Performance Optimization - Background Job Pattern
 */
@Serdeable
data class ExportJobDto(
    val jobId: String,
    val status: ExportJobStatus,
    val exportType: ExportType,
    val progressPercent: Int,
    val totalItems: Long,
    val processedItems: Long,
    val fileName: String?,
    val fileSizeBytes: Long?,
    val errorMessage: String?,
    val createdAt: LocalDateTime,
    val startedAt: LocalDateTime?,
    val completedAt: LocalDateTime?,
    val isDownloadable: Boolean,
    val isRunning: Boolean
) {
    companion object {
        fun fromEntity(job: ExportJob): ExportJobDto {
            return ExportJobDto(
                jobId = job.id,
                status = job.status,
                exportType = job.exportType,
                progressPercent = job.getProgressPercent(),
                totalItems = job.totalItems,
                processedItems = job.processedItems,
                fileName = job.fileName,
                fileSizeBytes = job.fileSizeBytes,
                errorMessage = job.errorMessage,
                createdAt = job.createdAt,
                startedAt = job.startedAt,
                completedAt = job.completedAt,
                isDownloadable = job.isDownloadable(),
                isRunning = job.isRunning()
            )
        }
    }
}

/**
 * Request to start an export job
 */
@Serdeable
data class StartExportRequest(
    val exportType: ExportType = ExportType.VULNERABILITIES
)

/**
 * Response when starting an export job
 */
@Serdeable
data class StartExportResponse(
    val jobId: String,
    val message: String,
    val status: ExportJobStatus
)
