package com.secman.service

import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.util.regex.Pattern

/**
 * Service for comprehensive input validation and sanitization
 */
@Singleton
class InputValidationService {
    
    private val logger = LoggerFactory.getLogger(InputValidationService::class.java)
    
    companion object {
        // Validation patterns
        private val EMAIL_PATTERN = Pattern.compile(
            "^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$"
        )
        
        private val PHONE_PATTERN = Pattern.compile(
            "^[+]?[(]?[0-9]{1,4}[)]?[-\\s.]?[(]?[0-9]{1,4}[)]?[-\\s.]?[0-9]{1,9}$"
        )
        
        private val ALPHANUMERIC_PATTERN = Pattern.compile("^[a-zA-Z0-9]+$")
        
        private val SAFE_TEXT_PATTERN = Pattern.compile("^[a-zA-Z0-9\\s.,!?@#$%^&*()_+=\\-\\[\\]{}|;:'\"`~<>/]+$")
        
        // SQL injection patterns
        private val SQL_INJECTION_PATTERNS = listOf(
            Pattern.compile("(\\b(SELECT|INSERT|UPDATE|DELETE|DROP|UNION|CREATE|ALTER|EXEC|EXECUTE)\\b)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(--|#|/\\*|\\*/|;\\s*--)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(\\bOR\\b\\s+[\\w'\"]+\\s*=\\s*[\\w'\"]+)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("('[\"`;]\\s*(OR|AND)\\s+[\\w'\"]+\\s*=)", Pattern.CASE_INSENSITIVE)
        )
        
        // XSS patterns
        private val XSS_PATTERNS = listOf(
            Pattern.compile("<script[^>]*>.*?</script>", Pattern.CASE_INSENSITIVE or Pattern.DOTALL),
            Pattern.compile("javascript:", Pattern.CASE_INSENSITIVE),
            Pattern.compile("on\\w+\\s*=", Pattern.CASE_INSENSITIVE),
            Pattern.compile("<iframe[^>]*>", Pattern.CASE_INSENSITIVE),
            Pattern.compile("<object[^>]*>", Pattern.CASE_INSENSITIVE),
            Pattern.compile("<embed[^>]*>", Pattern.CASE_INSENSITIVE),
            Pattern.compile("<applet[^>]*>", Pattern.CASE_INSENSITIVE)
        )
        
        // Command injection patterns
        private val COMMAND_INJECTION_PATTERNS = listOf(
            Pattern.compile("[;&|`\$]"),
            Pattern.compile("\\$\\([^)]*\\)"),
            Pattern.compile("`[^`]*`")
        )
        
        // Path traversal patterns
        private val PATH_TRAVERSAL_PATTERNS = listOf(
            Pattern.compile("\\.\\./"),
            Pattern.compile("\\.\\.\\\\"),
            Pattern.compile("%2e%2e[/\\\\]", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\.\\.%2f", Pattern.CASE_INSENSITIVE)
        )
        
        // Maximum lengths
        const val MAX_NAME_LENGTH = 100
        const val MAX_EMAIL_LENGTH = 255
        const val MAX_DESCRIPTION_LENGTH = 5000
        const val MAX_URL_LENGTH = 2048
        const val MAX_PHONE_LENGTH = 20
        const val MAX_ID_VALUE = 9999999999L
    }
    
    /**
     * Validate and sanitize email
     */
    fun validateEmail(email: String?): ValidationResult {
        if (email.isNullOrBlank()) {
            return ValidationResult(false, "Email is required")
        }
        
        if (email.length > MAX_EMAIL_LENGTH) {
            return ValidationResult(false, "Email is too long")
        }
        
        if (!EMAIL_PATTERN.matcher(email).matches()) {
            return ValidationResult(false, "Invalid email format")
        }
        
        if (containsSqlInjection(email) || containsXss(email)) {
            logger.warn("Potential injection attempt in email: {}", email)
            return ValidationResult(false, "Invalid email format")
        }
        
        return ValidationResult(true, sanitizeString(email))
    }
    
    /**
     * Validate and sanitize name fields
     */
    fun validateName(name: String?, fieldName: String = "Name"): ValidationResult {
        if (name.isNullOrBlank()) {
            return ValidationResult(false, "$fieldName is required")
        }
        
        if (name.length > MAX_NAME_LENGTH) {
            return ValidationResult(false, "$fieldName is too long")
        }
        
        if (containsSqlInjection(name) || containsXss(name)) {
            logger.warn("Potential injection attempt in {}: {}", fieldName, name)
            return ValidationResult(false, "$fieldName contains invalid characters")
        }
        
        return ValidationResult(true, sanitizeString(name))
    }
    
    /**
     * Validate and sanitize description/text fields
     */
    fun validateDescription(text: String?, fieldName: String = "Description"): ValidationResult {
        if (text == null) {
            return ValidationResult(true, "")
        }
        
        if (text.length > MAX_DESCRIPTION_LENGTH) {
            return ValidationResult(false, "$fieldName is too long")
        }
        
        if (containsSqlInjection(text) || containsXss(text)) {
            logger.warn("Potential injection attempt in {}: {}", fieldName, text.take(100))
            return ValidationResult(false, "$fieldName contains invalid content")
        }
        
        return ValidationResult(true, sanitizeString(text))
    }
    
    /**
     * Validate numeric ID
     */
    fun validateId(id: Long?): ValidationResult {
        if (id == null || id <= 0) {
            return ValidationResult(false, "Invalid ID")
        }
        
        if (id > MAX_ID_VALUE) {
            return ValidationResult(false, "ID value out of range")
        }
        
        return ValidationResult(true, id.toString())
    }
    
    /**
     * Validate URL
     */
    fun validateUrl(url: String?): ValidationResult {
        if (url.isNullOrBlank()) {
            return ValidationResult(false, "URL is required")
        }
        
        if (url.length > MAX_URL_LENGTH) {
            return ValidationResult(false, "URL is too long")
        }
        
        try {
            val uri = java.net.URI(url)
            if (uri.scheme !in setOf("http", "https")) {
                return ValidationResult(false, "Only HTTP and HTTPS URLs are allowed")
            }
        } catch (e: Exception) {
            return ValidationResult(false, "Invalid URL format")
        }
        
        if (containsSqlInjection(url) || containsXss(url)) {
            logger.warn("Potential injection attempt in URL: {}", url)
            return ValidationResult(false, "Invalid URL")
        }
        
        return ValidationResult(true, sanitizeString(url))
    }
    
    /**
     * Validate phone number
     */
    fun validatePhoneNumber(phone: String?): ValidationResult {
        if (phone.isNullOrBlank()) {
            return ValidationResult(true, "")
        }
        
        if (phone.length > MAX_PHONE_LENGTH) {
            return ValidationResult(false, "Phone number is too long")
        }
        
        if (!PHONE_PATTERN.matcher(phone).matches()) {
            return ValidationResult(false, "Invalid phone number format")
        }
        
        return ValidationResult(true, sanitizeString(phone))
    }
    
    /**
     * Validate alphanumeric string
     */
    fun validateAlphanumeric(input: String?, fieldName: String = "Field"): ValidationResult {
        if (input.isNullOrBlank()) {
            return ValidationResult(false, "$fieldName is required")
        }
        
        if (!ALPHANUMERIC_PATTERN.matcher(input).matches()) {
            return ValidationResult(false, "$fieldName must contain only letters and numbers")
        }
        
        return ValidationResult(true, input)
    }
    
    /**
     * Check for SQL injection patterns
     */
    private fun containsSqlInjection(input: String): Boolean {
        return SQL_INJECTION_PATTERNS.any { pattern ->
            pattern.matcher(input).find()
        }
    }
    
    /**
     * Check for XSS patterns
     */
    private fun containsXss(input: String): Boolean {
        return XSS_PATTERNS.any { pattern ->
            pattern.matcher(input).find()
        }
    }
    
    /**
     * Check for command injection patterns
     */
    fun containsCommandInjection(input: String): Boolean {
        return COMMAND_INJECTION_PATTERNS.any { pattern ->
            pattern.matcher(input).find()
        }
    }
    
    /**
     * Check for path traversal patterns
     */
    fun containsPathTraversal(input: String): Boolean {
        return PATH_TRAVERSAL_PATTERNS.any { pattern ->
            pattern.matcher(input).find()
        }
    }
    
    /**
     * Sanitize string by escaping HTML entities
     */
    fun sanitizeString(input: String): String {
        return input
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#x27;")
            .replace("/", "&#x2F;")
            .replace("\u0000", "") // Remove null bytes
            .trim()
    }
    
    /**
     * Sanitize string for safe display (removes HTML but preserves text)
     */
    fun sanitizeForDisplay(input: String): String {
        return input
            .replace(Regex("<[^>]*>"), "") // Remove HTML tags
            .replace(Regex("javascript:", RegexOption.IGNORE_CASE), "")
            .replace(Regex("on\\w+\\s*=", RegexOption.IGNORE_CASE), "")
            .replace("\u0000", "") // Remove null bytes
            .trim()
    }
    
    /**
     * Validate JSON string
     */
    fun validateJson(json: String?): ValidationResult {
        if (json.isNullOrBlank()) {
            return ValidationResult(false, "JSON is required")
        }
        
        try {
            // Basic JSON validation
            com.fasterxml.jackson.databind.ObjectMapper().readTree(json)
        } catch (e: Exception) {
            return ValidationResult(false, "Invalid JSON format")
        }
        
        if (containsSqlInjection(json) || containsXss(json)) {
            logger.warn("Potential injection attempt in JSON")
            return ValidationResult(false, "JSON contains invalid content")
        }
        
        return ValidationResult(true, json)
    }
    
    /**
     * Validate list of IDs
     */
    fun validateIdList(ids: List<Long>?): ValidationResult {
        if (ids.isNullOrEmpty()) {
            return ValidationResult(false, "ID list is required")
        }
        
        if (ids.size > 1000) {
            return ValidationResult(false, "Too many IDs provided")
        }
        
        for (id in ids) {
            val result = validateId(id)
            if (!result.isValid) {
                return result
            }
        }
        
        return ValidationResult(true, ids.joinToString(","))
    }
    
    /**
     * Validate date string
     */
    fun validateDateString(date: String?, format: String = "yyyy-MM-dd"): ValidationResult {
        if (date.isNullOrBlank()) {
            return ValidationResult(false, "Date is required")
        }
        
        try {
            val formatter = java.time.format.DateTimeFormatter.ofPattern(format)
            java.time.LocalDate.parse(date, formatter)
        } catch (e: Exception) {
            return ValidationResult(false, "Invalid date format")
        }
        
        return ValidationResult(true, date)
    }
    
    /**
     * Result class for validation
     */
    data class ValidationResult(
        val isValid: Boolean,
        val sanitizedValue: String,
        val errorMessage: String? = null
    ) {
        constructor(isValid: Boolean, errorMessage: String) : this(isValid, "", errorMessage)
    }
}