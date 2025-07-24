package com.secman.service

import com.secman.domain.RequirementFile
import com.secman.domain.RiskAssessmentRequirementFile
import com.secman.domain.User
import com.secman.repository.RequirementFileRepository
import com.secman.repository.RiskAssessmentRequirementFileRepository
import com.secman.repository.RiskAssessmentRepository
import com.secman.repository.RequirementRepository
import io.micronaut.http.multipart.CompletedFileUpload
import jakarta.inject.Singleton
import jakarta.transaction.Transactional
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.*

@Singleton
open class FileService(
    private val requirementFileRepository: RequirementFileRepository,
    private val riskAssessmentRequirementFileRepository: RiskAssessmentRequirementFileRepository,
    private val riskAssessmentRepository: RiskAssessmentRepository,
    private val requirementRepository: RequirementRepository
) {
    
    private val logger = LoggerFactory.getLogger(FileService::class.java)
    
    companion object {
        const val MAX_FILE_SIZE = 50 * 1024 * 1024L // 50MB
        const val UPLOAD_DIR = "uploads/requirement-files"
        
        val ALLOWED_CONTENT_TYPES = setOf(
            "application/pdf",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.ms-excel",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "image/jpeg",
            "image/png", 
            "image/gif",
            "text/plain",
            "text/csv"
        )
    }

    data class FileValidationResult(
        val isValid: Boolean,
        val errorMessage: String? = null
    )

    data class FileUploadResult(
        val success: Boolean,
        val requirementFile: RequirementFile? = null,
        val riskAssessmentRequirementFile: RiskAssessmentRequirementFile? = null,
        val errorMessage: String? = null
    )

    init {
        // Ensure upload directory exists
        try {
            val uploadPath = Paths.get(UPLOAD_DIR)
            if (!Files.exists(uploadPath)) {
                Files.createDirectories(uploadPath)
                logger.info("Created upload directory: {}", uploadPath.toAbsolutePath())
            }
        } catch (e: IOException) {
            logger.error("Failed to create upload directory: {}", e.message, e)
        }
    }

    /**
     * Validate uploaded file
     */
    fun validateFile(fileUpload: CompletedFileUpload): FileValidationResult {
        // Check file size
        if (fileUpload.size > MAX_FILE_SIZE) {
            return FileValidationResult(
                false, 
                "File size exceeds maximum allowed size of ${MAX_FILE_SIZE / (1024 * 1024)}MB"
            )
        }

        // Check content type
        val contentType = fileUpload.contentType.map { it.toString() }.orElse("unknown")
        if (contentType !in ALLOWED_CONTENT_TYPES) {
            return FileValidationResult(
                false,
                "File type '$contentType' is not allowed. Allowed types: ${ALLOWED_CONTENT_TYPES.joinToString(", ")}"
            )
        }

        // Check filename
        val originalFilename = fileUpload.filename
        if (originalFilename.isBlank()) {
            return FileValidationResult(false, "Filename cannot be empty")
        }

        if (originalFilename.length > 255) {
            return FileValidationResult(false, "Filename is too long (max 255 characters)")
        }

        return FileValidationResult(true)
    }

    /**
     * Generate unique filename
     */
    fun generateUniqueFilename(
        riskAssessmentId: Long,
        requirementId: Long,
        originalFilename: String
    ): String {
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        val extension = getFileExtension(originalFilename)
        return "${riskAssessmentId}_${requirementId}_${timestamp}${extension}"
    }

    /**
     * Get file extension from filename
     */
    private fun getFileExtension(filename: String): String {
        val lastDot = filename.lastIndexOf('.')
        return if (lastDot > 0 && lastDot < filename.length - 1) {
            filename.substring(lastDot)
        } else {
            ""
        }
    }

    /**
     * Upload file and save to database
     */
    @Transactional
    open fun uploadFile(
        fileUpload: CompletedFileUpload,
        riskAssessmentId: Long,
        requirementId: Long,
        uploadedBy: User
    ): FileUploadResult {
        try {
            // Validate file
            val validation = validateFile(fileUpload)
            if (!validation.isValid) {
                return FileUploadResult(false, errorMessage = validation.errorMessage)
            }

            // Check that risk assessment and requirement exist
            val riskAssessment = riskAssessmentRepository.findById(riskAssessmentId)
            if (riskAssessment.isEmpty) {
                return FileUploadResult(false, errorMessage = "Risk assessment not found")
            }

            val requirement = requirementRepository.findById(requirementId)
            if (requirement.isEmpty) {
                return FileUploadResult(false, errorMessage = "Requirement not found")
            }

            // Generate unique filename
            val originalFilename = fileUpload.filename
            val uniqueFilename = generateUniqueFilename(riskAssessmentId, requirementId, originalFilename)
            
            // Save file to disk
            val filePath = Paths.get(UPLOAD_DIR, uniqueFilename)
            val absolutePath = filePath.toAbsolutePath()
            
            Files.copy(fileUpload.inputStream, absolutePath, StandardCopyOption.REPLACE_EXISTING)
            
            logger.info("File saved to disk: {}", absolutePath)

            // Save metadata to database
            val requirementFile = RequirementFile(
                filename = uniqueFilename,
                originalFilename = originalFilename,
                filePath = absolutePath.toString(),
                fileSize = fileUpload.size,
                contentType = fileUpload.contentType.map { it.toString() }.orElse("application/octet-stream"),
                uploadedBy = uploadedBy
            )

            val savedFile = requirementFileRepository.save(requirementFile)

            // Create link record
            val linkRecord = RiskAssessmentRequirementFile(
                riskAssessment = riskAssessment.get(),
                requirement = requirement.get(),
                file = savedFile,
                uploadedBy = uploadedBy
            )

            val savedLink = riskAssessmentRequirementFileRepository.save(linkRecord)

            logger.info("File uploaded successfully: {} -> {}", originalFilename, uniqueFilename)

            return FileUploadResult(
                success = true,
                requirementFile = savedFile,
                riskAssessmentRequirementFile = savedLink
            )

        } catch (e: IOException) {
            logger.error("Failed to upload file: {}", e.message, e)
            return FileUploadResult(false, errorMessage = "Failed to save file: ${e.message}")
        } catch (e: Exception) {
            logger.error("Unexpected error during file upload: {}", e.message, e)
            return FileUploadResult(false, errorMessage = "Unexpected error: ${e.message}")
        }
    }

    /**
     * Get file by ID
     */
    fun getFile(fileId: Long): Optional<RequirementFile> {
        return requirementFileRepository.findById(fileId)
    }

    /**
     * Get file content for download
     */
    fun getFileContent(fileId: Long): Pair<RequirementFile, ByteArray>? {
        val fileOptional = requirementFileRepository.findById(fileId)
        if (fileOptional.isEmpty) {
            return null
        }

        val file = fileOptional.get()
        val path = Paths.get(file.filePath)
        
        return try {
            val content = Files.readAllBytes(path)
            Pair(file, content)
        } catch (e: IOException) {
            logger.error("Failed to read file content: {}", e.message, e)
            null
        }
    }

    /**
     * Delete file (both from disk and database)
     */
    @Transactional
    open fun deleteFile(fileId: Long, deletedBy: User): Boolean {
        try {
            val fileOptional = requirementFileRepository.findById(fileId)
            if (fileOptional.isEmpty) {
                return false
            }

            val file = fileOptional.get()
            
            // Check permissions - user can delete their own files, or admin can delete any
            if (file.uploadedBy.id != deletedBy.id && !deletedBy.isAdmin()) {
                logger.warn("User {} attempted to delete file {} owned by {}", 
                    deletedBy.username, fileId, file.uploadedBy.username)
                return false
            }

            // Delete from disk
            val path = Paths.get(file.filePath)
            if (Files.exists(path)) {
                Files.delete(path)
                logger.info("Deleted file from disk: {}", path)
            }

            // Delete link records first
            riskAssessmentRequirementFileRepository.deleteByFileId(fileId)
            
            // Delete file record
            requirementFileRepository.deleteById(fileId)
            
            logger.info("File deleted successfully: {}", file.originalFilename)
            return true

        } catch (e: Exception) {
            logger.error("Failed to delete file {}: {}", fileId, e.message, e)
            return false
        }
    }

    /**
     * List files for risk assessment and requirement
     */
    fun getFilesForRequirement(
        riskAssessmentId: Long,
        requirementId: Long
    ): List<RiskAssessmentRequirementFile> {
        return riskAssessmentRequirementFileRepository.findByRiskAssessmentAndRequirement(
            riskAssessmentId, requirementId
        )
    }

    /**
     * Get file statistics for user
     */
    fun getFileStatistics(userId: Long): Map<String, Any> {
        val fileCount = requirementFileRepository.countByUploadedById(userId)
        val totalSize = requirementFileRepository.getTotalFileSizeByUserId(userId) ?: 0L
        
        return mapOf(
            "fileCount" to fileCount,
            "totalSize" to totalSize,
            "totalSizeMB" to (totalSize / (1024.0 * 1024.0)).let { "%.2f".format(it) }
        )
    }
}