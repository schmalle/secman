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
            "application/vnd.ms-powerpoint",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "image/jpeg",
            "image/png", 
            "image/gif",
            "image/bmp",
            "image/tiff",
            "text/plain",
            "text/csv"
        )
        
        // Dangerous file extensions that should be blocked
        val BLOCKED_EXTENSIONS = setOf(
            ".exe", ".bat", ".cmd", ".com", ".scr", ".vbs", ".vbe",
            ".js", ".jse", ".wsf", ".wsh", ".ps1", ".psm1", ".psd1",
            ".msi", ".msp", ".msc", ".jar", ".app", ".deb", ".rpm",
            ".dmg", ".pkg", ".run", ".sh", ".bash", ".zsh", ".fish",
            ".html", ".htm", ".xhtml", ".svg", ".xml", ".xsl", ".xslt",
            ".php", ".jsp", ".asp", ".aspx", ".py", ".rb", ".pl"
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
     * Validate uploaded file with enhanced security checks
     */
    fun validateFile(fileUpload: CompletedFileUpload): FileValidationResult {
        // Check file size
        if (fileUpload.size > MAX_FILE_SIZE) {
            return FileValidationResult(
                false, 
                "File size exceeds maximum allowed size of ${MAX_FILE_SIZE / (1024 * 1024)}MB"
            )
        }
        
        // Minimum file size check (prevent empty files)
        if (fileUpload.size == 0L) {
            return FileValidationResult(false, "File cannot be empty")
        }

        // Check content type
        val contentType = fileUpload.contentType.map { it.toString() }.orElse("unknown")
        if (contentType !in ALLOWED_CONTENT_TYPES) {
            return FileValidationResult(
                false,
                "File type '$contentType' is not allowed for security reasons"
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
        
        // Check for dangerous extensions
        val lowerFilename = originalFilename.lowercase()
        for (ext in BLOCKED_EXTENSIONS) {
            if (lowerFilename.endsWith(ext)) {
                logger.warn("Blocked upload attempt with dangerous extension: {}", originalFilename)
                return FileValidationResult(
                    false,
                    "File type not allowed for security reasons"
                )
            }
        }
        
        // Check for path traversal attempts in filename
        if (originalFilename.contains("..") || originalFilename.contains("/") || originalFilename.contains("\\")) {
            logger.warn("Potential path traversal attempt in filename: {}", originalFilename)
            return FileValidationResult(
                false,
                "Invalid characters in filename"
            )
        }
        
        // Check for null bytes
        if (originalFilename.contains("\u0000")) {
            logger.warn("Null byte detected in filename: {}", originalFilename)
            return FileValidationResult(
                false,
                "Invalid filename"
            )
        }
        
        // Validate extension matches content type
        val extension = getFileExtension(originalFilename).lowercase()
        if (!isExtensionValidForContentType(extension, contentType)) {
            logger.warn("File extension {} doesn't match content type {}", extension, contentType)
            return FileValidationResult(
                false,
                "File extension does not match content type"
            )
        }

        return FileValidationResult(true)
    }
    
    /**
     * Check if file extension is valid for the given content type
     */
    private fun isExtensionValidForContentType(extension: String, contentType: String): Boolean {
        return when (contentType) {
            "application/pdf" -> extension == ".pdf"
            "application/msword" -> extension == ".doc"
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> extension == ".docx"
            "application/vnd.ms-excel" -> extension == ".xls"
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> extension == ".xlsx"
            "application/vnd.ms-powerpoint" -> extension == ".ppt"
            "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> extension == ".pptx"
            "text/plain" -> extension in setOf(".txt", ".text")
            "text/csv" -> extension == ".csv"
            "image/jpeg" -> extension in setOf(".jpg", ".jpeg")
            "image/png" -> extension == ".png"
            "image/gif" -> extension == ".gif"
            "image/bmp" -> extension == ".bmp"
            "image/tiff" -> extension in setOf(".tif", ".tiff")
            else -> false
        }
    }

    /**
     * Generate unique filename with security considerations
     */
    fun generateUniqueFilename(
        riskAssessmentId: Long,
        requirementId: Long,
        originalFilename: String
    ): String {
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        val uuid = UUID.randomUUID().toString().substring(0, 8)
        val extension = getFileExtension(originalFilename)
        // Use UUID to prevent filename enumeration attacks
        return "${riskAssessmentId}_${requirementId}_${timestamp}_${uuid}${extension}"
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
            
            // Save file to disk with security checks
            val filePath = Paths.get(UPLOAD_DIR, uniqueFilename).normalize()
            val uploadDir = Paths.get(UPLOAD_DIR).normalize().toAbsolutePath()
            val absolutePath = filePath.toAbsolutePath()
            
            // Ensure the file will be saved within the upload directory
            if (!absolutePath.startsWith(uploadDir)) {
                logger.error("Path traversal attempt detected: {}", absolutePath)
                return FileUploadResult(false, errorMessage = "Security violation: Invalid file path")
            }
            
            // Set restrictive permissions on the file (owner read/write only)
            Files.copy(fileUpload.inputStream, absolutePath, StandardCopyOption.REPLACE_EXISTING)
            try {
                val file = absolutePath.toFile()
                file.setReadable(false, false)
                file.setWritable(false, false)
                file.setExecutable(false, false)
                file.setReadable(true, true)
                file.setWritable(true, true)
            } catch (e: Exception) {
                logger.warn("Could not set file permissions: {}", e.message)
            }
            
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
     * Get file content for download with path validation
     */
    fun getFileContent(fileId: Long): Pair<RequirementFile, ByteArray>? {
        val fileOptional = requirementFileRepository.findById(fileId)
        if (fileOptional.isEmpty) {
            return null
        }

        val file = fileOptional.get()
        val path = Paths.get(file.filePath).normalize()
        val uploadDir = Paths.get(UPLOAD_DIR).normalize().toAbsolutePath()
        
        // Validate the file path is within upload directory
        if (!path.toAbsolutePath().startsWith(uploadDir)) {
            logger.error("Path traversal attempt when reading file: {}", path)
            return null
        }
        
        // Check file exists and is readable
        if (!Files.exists(path) || !Files.isReadable(path)) {
            logger.error("File not found or not readable: {}", path)
            return null
        }
        
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