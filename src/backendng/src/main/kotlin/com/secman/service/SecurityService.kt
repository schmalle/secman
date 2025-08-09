package com.secman.service

import com.secman.domain.RiskAssessment
import com.secman.domain.User
import com.secman.repository.RiskAssessmentRepository
import com.secman.repository.RiskAssessmentRequirementFileRepository
import com.secman.repository.RequirementFileRepository
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.nio.file.Paths
import java.util.regex.Pattern

/**
 * Security service for authorization checks and input validation
 */
@Singleton
open class SecurityService(
    private val riskAssessmentRepository: RiskAssessmentRepository,
    private val requirementFileRepository: RequirementFileRepository,
    private val riskAssessmentRequirementFileRepository: RiskAssessmentRequirementFileRepository
) {
    
    private val logger = LoggerFactory.getLogger(SecurityService::class.java)
    
    companion object {
        // Input validation patterns
        private val EMAIL_PATTERN = Pattern.compile(
            "^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$"
        )
        
        private val USERNAME_PATTERN = Pattern.compile(
            "^[a-zA-Z0-9_-]{3,50}$"
        )
        
        private val SAFE_FILENAME_PATTERN = Pattern.compile(
            "^[a-zA-Z0-9._-]+$"
        )
        
        // Dangerous file extensions that should never be allowed
        private val DANGEROUS_EXTENSIONS = setOf(
            ".exe", ".bat", ".cmd", ".com", ".scr", ".vbs", ".vbe",
            ".js", ".jse", ".wsf", ".wsh", ".ps1", ".psm1", ".psd1",
            ".msi", ".msp", ".msc", ".jar", ".app", ".deb", ".rpm",
            ".dmg", ".pkg", ".run", ".sh", ".bash", ".zsh", ".fish",
            ".html", ".htm", ".xhtml", ".svg", ".xml", ".xsl", ".xslt"
        )
        
        // Additional MIME type restrictions
        private val STRICTLY_ALLOWED_MIME_TYPES = setOf(
            "application/pdf",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.ms-excel",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.ms-powerpoint",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "text/plain",
            "text/csv",
            "image/jpeg",
            "image/png",
            "image/gif",
            "image/bmp",
            "image/tiff"
        )
        
        // Maximum allowed path depth to prevent directory traversal
        private const val MAX_PATH_DEPTH = 5
        
        // Maximum input lengths
        const val MAX_USERNAME_LENGTH = 50
        const val MAX_EMAIL_LENGTH = 255
        const val MAX_FILENAME_LENGTH = 255
        const val MAX_TEXT_INPUT_LENGTH = 10000
        const val MAX_URL_LENGTH = 2048
    }
    
    /**
     * Check if user has access to a specific risk assessment
     */
    fun userHasAccessToRiskAssessment(user: User, riskAssessmentId: Long): Boolean {
        // Admins have access to everything
        if (user.isAdmin()) {
            return true
        }
        
        val riskAssessmentOpt = riskAssessmentRepository.findById(riskAssessmentId)
        if (riskAssessmentOpt.isEmpty) {
            return false
        }
        
        val riskAssessment = riskAssessmentOpt.get()
        
        // For now, allow access to all authenticated users for their assessments
        // In a production system, you would check:
        // - If user created the assessment
        // - If user is assigned to the demand/asset
        // - If user has specific role permissions
        // This is a simplified implementation
        
        return true
    }
    
    /**
     * Check if user has access to a specific file
     */
    fun userHasAccessToFile(user: User, fileId: Long): Boolean {
        // Admins have access to everything
        if (user.isAdmin()) {
            return true
        }
        
        val fileOpt = requirementFileRepository.findById(fileId)
        if (fileOpt.isEmpty) {
            return false
        }
        
        val file = fileOpt.get()
        
        // Check if user uploaded the file
        if (file.uploadedBy.id == user.id) {
            return true
        }
        
        // Check if file is associated with a risk assessment the user has access to
        val associationOpt = riskAssessmentRequirementFileRepository.findByFileId(fileId)
        if (associationOpt.isPresent) {
            val association = associationOpt.get()
            return userHasAccessToRiskAssessment(user, association.riskAssessment.id!!)
        }
        
        return false
    }
    
    /**
     * Check if user can delete a specific file
     */
    fun userCanDeleteFile(user: User, fileId: Long): Boolean {
        // Admins can delete any file
        if (user.isAdmin()) {
            return true
        }
        
        val fileOpt = requirementFileRepository.findById(fileId)
        if (fileOpt.isEmpty) {
            return false
        }
        
        val file = fileOpt.get()
        
        // Users can only delete their own files
        return file.uploadedBy.id == user.id
    }
    
    /**
     * Validate email format
     */
    fun isValidEmail(email: String): Boolean {
        if (email.length > MAX_EMAIL_LENGTH) {
            return false
        }
        return EMAIL_PATTERN.matcher(email).matches()
    }
    
    /**
     * Validate username format
     */
    fun isValidUsername(username: String): Boolean {
        if (username.length > MAX_USERNAME_LENGTH) {
            return false
        }
        return USERNAME_PATTERN.matcher(username).matches()
    }
    
    /**
     * Validate and sanitize filename
     */
    fun validateAndSanitizeFilename(filename: String): Pair<Boolean, String?> {
        if (filename.isBlank() || filename.length > MAX_FILENAME_LENGTH) {
            return Pair(false, "Invalid filename length")
        }
        
        // Check for dangerous extensions
        val lowerFilename = filename.lowercase()
        for (ext in DANGEROUS_EXTENSIONS) {
            if (lowerFilename.endsWith(ext)) {
                return Pair(false, "File type not allowed for security reasons")
            }
        }
        
        // Check for path traversal attempts
        if (filename.contains("..") || filename.contains("/") || filename.contains("\\")) {
            return Pair(false, "Invalid characters in filename")
        }
        
        // Check against safe pattern
        if (!SAFE_FILENAME_PATTERN.matcher(filename).matches()) {
            return Pair(false, "Filename contains invalid characters")
        }
        
        return Pair(true, null)
    }
    
    /**
     * Validate MIME type against strict allowlist
     */
    fun isAllowedMimeType(mimeType: String): Boolean {
        return mimeType in STRICTLY_ALLOWED_MIME_TYPES
    }
    
    /**
     * Validate file extension matches MIME type
     */
    fun validateFileExtensionMatchesMimeType(filename: String, mimeType: String): Boolean {
        val extension = filename.substringAfterLast('.', "").lowercase()
        
        return when (mimeType) {
            "application/pdf" -> extension == "pdf"
            "application/msword" -> extension == "doc"
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> extension == "docx"
            "application/vnd.ms-excel" -> extension == "xls"
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> extension == "xlsx"
            "application/vnd.ms-powerpoint" -> extension == "ppt"
            "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> extension == "pptx"
            "text/plain" -> extension in setOf("txt", "text")
            "text/csv" -> extension == "csv"
            "image/jpeg" -> extension in setOf("jpg", "jpeg")
            "image/png" -> extension == "png"
            "image/gif" -> extension == "gif"
            "image/bmp" -> extension == "bmp"
            "image/tiff" -> extension in setOf("tif", "tiff")
            else -> false
        }
    }
    
    /**
     * Sanitize text input to prevent XSS and injection attacks
     */
    fun sanitizeTextInput(input: String, maxLength: Int = MAX_TEXT_INPUT_LENGTH): String {
        // Truncate to max length
        val truncated = if (input.length > maxLength) {
            input.substring(0, maxLength)
        } else {
            input
        }
        
        // Remove null bytes
        val noNullBytes = truncated.replace("\u0000", "")
        
        // Escape HTML special characters
        return noNullBytes
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#x27;")
            .replace("/", "&#x2F;")
    }
    
    /**
     * Validate and sanitize path to prevent directory traversal
     */
    fun validatePath(path: String, basePath: String): Boolean {
        try {
            val normalizedPath = Paths.get(path).normalize()
            val normalizedBasePath = Paths.get(basePath).normalize()
            
            // Check if the path is within the base path
            if (!normalizedPath.startsWith(normalizedBasePath)) {
                logger.warn("Path traversal attempt detected: {} not under {}", path, basePath)
                return false
            }
            
            // Check path depth
            val depth = normalizedPath.nameCount - normalizedBasePath.nameCount
            if (depth > MAX_PATH_DEPTH) {
                logger.warn("Path too deep: {} levels", depth)
                return false
            }
            
            return true
        } catch (e: Exception) {
            logger.error("Path validation error: {}", e.message)
            return false
        }
    }
    
    /**
     * Validate numeric ID to prevent injection
     */
    fun isValidId(id: Long): Boolean {
        return id > 0 && id < Long.MAX_VALUE
    }
    
    /**
     * Validate URL format
     */
    fun isValidUrl(url: String): Boolean {
        if (url.length > MAX_URL_LENGTH) {
            return false
        }
        
        return try {
            val uri = java.net.URI(url)
            // Only allow http and https protocols
            uri.scheme in setOf("http", "https")
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Check if string contains SQL injection patterns
     */
    fun containsSqlInjectionPattern(input: String): Boolean {
        val sqlPatterns = listOf(
            "';", "--", "/*", "*/", "xp_", "sp_", "0x",
            "union", "select", "insert", "update", "delete", "drop",
            "exec", "execute", "script", "javascript:", "onload=",
            "onerror=", "onclick=", "onmouseover="
        )
        
        val lowerInput = input.lowercase()
        return sqlPatterns.any { pattern -> 
            lowerInput.contains(pattern)
        }
    }
    
    /**
     * Generate a secure random token
     */
    fun generateSecureToken(): String {
        val random = java.security.SecureRandom()
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}