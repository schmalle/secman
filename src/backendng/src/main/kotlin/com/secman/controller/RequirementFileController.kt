package com.secman.controller

import com.secman.domain.RequirementFile
import com.secman.domain.RequirementFileResponse
import com.secman.domain.User
import com.secman.repository.RequirementFileRepository
import com.secman.repository.RiskAssessmentRequirementFileRepository
import com.secman.repository.UserRepository
import com.secman.service.FileService
import io.micronaut.http.HttpResponse
import io.micronaut.http.MediaType
import io.micronaut.http.annotation.*
import io.micronaut.http.multipart.CompletedFileUpload
import io.micronaut.http.server.types.files.StreamedFile
import io.micronaut.scheduling.TaskExecutors
import io.micronaut.scheduling.annotation.ExecuteOn
import io.micronaut.security.annotation.Secured
import io.micronaut.security.authentication.Authentication
import io.micronaut.security.rules.SecurityRule
import io.micronaut.serde.annotation.Serdeable
import jakarta.transaction.Transactional
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.time.LocalDateTime

@Controller("/api")
@Secured(SecurityRule.IS_AUTHENTICATED)
@ExecuteOn(TaskExecutors.BLOCKING)
open class RequirementFileController(
    private val fileService: FileService,
    private val requirementFileRepository: RequirementFileRepository,
    private val riskAssessmentRequirementFileRepository: RiskAssessmentRequirementFileRepository,
    private val userRepository: UserRepository
) {
    
    private val logger = LoggerFactory.getLogger(RequirementFileController::class.java)

    @Serdeable
    data class ErrorResponse(
        val error: String,
        val timestamp: LocalDateTime = LocalDateTime.now()
    )

    @Serdeable
    data class FileUploadResponse(
        val success: Boolean,
        val message: String,
        val file: RequirementFileResponse? = null
    )

    @Serdeable
    data class FileListResponse(
        val files: List<RequirementFileResponse>
    )

    /**
     * Upload file for specific risk assessment and requirement
     */
    @Post("/risk-assessments/{riskAssessmentId}/requirements/{requirementId}/files")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Transactional
    open fun uploadFile(
        @PathVariable riskAssessmentId: Long,
        @PathVariable requirementId: Long,
        @Part file: CompletedFileUpload,
        authentication: Authentication
    ): HttpResponse<*> {
        try {
            val username = authentication.name
            val userOptional = userRepository.findByUsername(username)
            
            if (userOptional.isEmpty) {
                return HttpResponse.unauthorized<Any>().body(ErrorResponse("User not found"))
            }

            val user = userOptional.get()
            
            logger.info("User {} uploading file {} for risk assessment {} and requirement {}", 
                username, file.filename, riskAssessmentId, requirementId)

            val result = fileService.uploadFile(file, riskAssessmentId, requirementId, user)
            
            return if (result.success && result.requirementFile != null) {
                HttpResponse.created(FileUploadResponse(
                    success = true,
                    message = "File uploaded successfully",
                    file = result.requirementFile.toSafeResponse()
                ))
            } else {
                HttpResponse.badRequest(FileUploadResponse(
                    success = false,
                    message = result.errorMessage ?: "Unknown error occurred"
                ))
            }

        } catch (e: Exception) {
            logger.error("Error uploading file: {}", e.message, e)
            return HttpResponse.serverError(ErrorResponse("Internal server error: ${e.message}"))
        }
    }

    /**
     * List files for specific risk assessment and requirement
     */
    @Get("/risk-assessments/{riskAssessmentId}/requirements/{requirementId}/files")
    open fun listFiles(
        @PathVariable riskAssessmentId: Long,
        @PathVariable requirementId: Long,
        authentication: Authentication
    ): HttpResponse<*> {
        try {
            val files = fileService.getFilesForRequirement(riskAssessmentId, requirementId)
            
            val fileResponses = files.map { linkRecord ->
                linkRecord.file.toSafeResponse()
            }

            return HttpResponse.ok(FileListResponse(fileResponses))

        } catch (e: Exception) {
            logger.error("Error listing files: {}", e.message, e)
            return HttpResponse.serverError(ErrorResponse("Failed to list files: ${e.message}"))
        }
    }

    /**
     * Download file by ID
     */
    @Get("/files/{fileId}/download")
    open fun downloadFile(
        @PathVariable fileId: Long,
        authentication: Authentication
    ): HttpResponse<*> {
        try {
            val fileContent = fileService.getFileContent(fileId)
            
            if (fileContent == null) {
                return HttpResponse.notFound<Any>().body(ErrorResponse("File not found"))
            }

            val (file, content) = fileContent
            val inputStream = ByteArrayInputStream(content)
            
            logger.info("User {} downloading file: {}", authentication.name, file.originalFilename)

            return HttpResponse.ok(StreamedFile(inputStream, MediaType.of(file.contentType)))
                .header("Content-Disposition", "attachment; filename=\"${file.originalFilename}\"")
                .header("Content-Length", file.fileSize.toString())

        } catch (e: Exception) {
            logger.error("Error downloading file {}: {}", fileId, e.message, e)
            return HttpResponse.serverError(ErrorResponse("Failed to download file: ${e.message}"))
        }
    }

    /**
     * Get file metadata by ID
     */
    @Get("/files/{fileId}")
    open fun getFileMetadata(
        @PathVariable fileId: Long,
        authentication: Authentication
    ): HttpResponse<*> {
        try {
            val fileOptional = fileService.getFile(fileId)
            
            if (fileOptional.isEmpty) {
                return HttpResponse.notFound<Any>().body(ErrorResponse("File not found"))
            }

            val file = fileOptional.get()
            return HttpResponse.ok(file.toSafeResponse())

        } catch (e: Exception) {
            logger.error("Error getting file metadata {}: {}", fileId, e.message, e)
            return HttpResponse.serverError(ErrorResponse("Failed to get file metadata: ${e.message}"))
        }
    }

    /**
     * Delete file by ID
     */
    @Delete("/files/{fileId}")
    @Transactional
    open fun deleteFile(
        @PathVariable fileId: Long,
        authentication: Authentication
    ): HttpResponse<*> {
        try {
            val username = authentication.name
            val userOptional = userRepository.findByUsername(username)
            
            if (userOptional.isEmpty) {
                return HttpResponse.unauthorized<Any>().body(ErrorResponse("User not found"))
            }

            val user = userOptional.get()
            
            logger.info("User {} deleting file {}", username, fileId)

            val success = fileService.deleteFile(fileId, user)
            
            return if (success) {
                HttpResponse.ok(mapOf("message" to "File deleted successfully"))
            } else {
                HttpResponse.badRequest(ErrorResponse("Failed to delete file or insufficient permissions"))
            }

        } catch (e: Exception) {
            logger.error("Error deleting file {}: {}", fileId, e.message, e)
            return HttpResponse.serverError(ErrorResponse("Failed to delete file: ${e.message}"))
        }
    }

    /**
     * List all files uploaded by current user
     */
    @Get("/files/my-files")
    open fun getMyFiles(authentication: Authentication): HttpResponse<*> {
        try {
            val username = authentication.name
            val userOptional = userRepository.findByUsername(username)
            
            if (userOptional.isEmpty) {
                return HttpResponse.unauthorized<Any>().body(ErrorResponse("User not found"))
            }

            val user = userOptional.get()
            val files = requirementFileRepository.findByUploadedById(user.id!!)
            
            val fileResponses = files.map { it.toSafeResponse() }
            
            return HttpResponse.ok(FileListResponse(fileResponses))

        } catch (e: Exception) {
            logger.error("Error getting user files: {}", e.message, e)
            return HttpResponse.serverError(ErrorResponse("Failed to get user files: ${e.message}"))
        }
    }

    /**
     * Get file upload statistics for current user
     */
    @Get("/files/statistics")
    open fun getFileStatistics(authentication: Authentication): HttpResponse<*> {
        try {
            val username = authentication.name
            val userOptional = userRepository.findByUsername(username)
            
            if (userOptional.isEmpty) {
                return HttpResponse.unauthorized<Any>().body(ErrorResponse("User not found"))
            }

            val user = userOptional.get()
            val statistics = fileService.getFileStatistics(user.id!!)
            
            return HttpResponse.ok(statistics)

        } catch (e: Exception) {
            logger.error("Error getting file statistics: {}", e.message, e)
            return HttpResponse.serverError(ErrorResponse("Failed to get file statistics: ${e.message}"))
        }
    }

    /**
     * Get allowed file types and size limits
     */
    @Get("/files/config")
    open fun getFileConfig(): HttpResponse<Map<String, Any>> {
        return HttpResponse.ok(mapOf(
            "maxFileSize" to FileService.MAX_FILE_SIZE,
            "maxFileSizeMB" to (FileService.MAX_FILE_SIZE / (1024 * 1024)),
            "allowedContentTypes" to FileService.ALLOWED_CONTENT_TYPES.sorted(),
            "uploadDirectory" to FileService.UPLOAD_DIR
        ))
    }
}