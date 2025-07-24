package com.secman.service

import com.secman.domain.Norm
import com.secman.repository.NormRepository
import io.micronaut.transaction.annotation.Transactional
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.util.*

@Singleton
open class NormParsingService(
    private val normRepository: NormRepository
) {
    
    private val log = LoggerFactory.getLogger(NormParsingService::class.java)
    
    companion object {
        // Regex patterns for different norm formats
        private val ISO_PATTERN = Regex("^(ISO\\s*\\d+)(?::(\\d{4}))?(?:[:\\s]+(.+))?", RegexOption.IGNORE_CASE)
        private val NIST_PATTERN = Regex("^(NIST\\s+[A-Z]+(?:\\s+[\\d\\w-]+)*)(?:\\s*:\\s*(.+))?", RegexOption.IGNORE_CASE)
        private val IEC_PATTERN = Regex("^(IEC\\s*\\d+(?:-\\d+)*(?:-\\d+)?)(?::(\\d{4}))?(?:[:\\s]+(.+))?", RegexOption.IGNORE_CASE)
        
        // Year validation range
        private const val MIN_YEAR = 1990
        private const val MAX_YEAR = 2030
        
        // Multi-norm separators
        private val NORM_SEPARATORS = arrayOf("•", ";", ", ", "|")
    }
    
    /**
     * Parse a norm string that may contain multiple norms separated by various delimiters
     * Returns a set of parsed and persisted Norm entities
     */
    @Transactional
    open fun parseNorms(normString: String?): Set<Norm> {
        if (normString.isNullOrBlank()) {
            log.debug("Empty norm string provided")
            return emptySet()
        }
        
        val cleanedString = normString.trim()
        log.debug("Parsing norm string: {}", cleanedString)
        
        val result = mutableSetOf<Norm>()
        
        // Split by multiple separators
        val normParts = splitNormString(cleanedString)
        
        for (part in normParts) {
            val trimmedPart = part.trim()
            if (trimmedPart.isNotEmpty()) {
                try {
                    val norm = parseAndCreateSingleNorm(trimmedPart)
                    if (norm != null) {
                        result.add(norm)
                    }
                } catch (e: Exception) {
                    log.warn("Failed to parse norm part '{}': {}", trimmedPart, e.message)
                }
            }
        }
        
        log.debug("Successfully parsed {} norms from string", result.size)
        return result
    }
    
    /**
     * Split norm string by various separators
     */
    private fun splitNormString(normString: String): List<String> {
        var parts = listOf(normString)
        
        for (separator in NORM_SEPARATORS) {
            parts = parts.flatMap { it.split(separator) }
        }
        
        return parts.filter { it.trim().isNotEmpty() }
    }
    
    /**
     * Parse a single norm string and create/retrieve the corresponding Norm entity
     */
    @Transactional
    open fun parseAndCreateSingleNorm(normString: String): Norm? {
        val cleaned = cleanNormString(normString)
        
        if (!isValidNormFormat(cleaned)) {
            log.warn("Invalid norm format: {}", cleaned)
            return null
        }
        
        val parseResult = parseNormFormat(cleaned)
        if (parseResult == null) {
            log.warn("Failed to parse norm: {}", cleaned)
            return null
        }
        
        return findOrCreateNorm(parseResult)
    }
    
    /**
     * Clean the norm string by removing trailing periods and normalizing whitespace
     */
    private fun cleanNormString(normString: String): String {
        return normString.trim()
            .replace(Regex("\\s+"), " ")
            .removeSuffix(".")
            .trim()
    }
    
    /**
     * Check if the norm string matches any of the supported formats
     */
    private fun isValidNormFormat(normString: String): Boolean {
        return ISO_PATTERN.containsMatchIn(normString) ||
               NIST_PATTERN.containsMatchIn(normString) ||
               IEC_PATTERN.containsMatchIn(normString)
    }
    
    /**
     * Parse the norm string into structured components
     */
    private fun parseNormFormat(normString: String): NormParseResult? {
        // Try ISO pattern first
        ISO_PATTERN.find(normString)?.let { match ->
            val baseName = normalizeIsoName(match.groupValues[1])
            val year = match.groupValues[2].takeIf { it.isNotEmpty() }?.toIntOrNull()
            val section = match.groupValues[3].takeIf { it.isNotEmpty() }
            
            val fullName = if (section != null) {
                "$baseName: $section"
            } else {
                baseName
            }
            
            val version = year?.toString() ?: ""
            
            return NormParseResult(
                name = fullName,
                version = version,
                year = if (isValidYear(year)) year else null,
                type = NormType.ISO
            )
        }
        
        // Try NIST pattern
        NIST_PATTERN.find(normString)?.let { match ->
            val baseName = normalizeNistName(match.groupValues[1])
            val section = match.groupValues[2].takeIf { it.isNotEmpty() }
            
            val fullName = if (section != null) {
                "$baseName: $section"
            } else {
                baseName
            }
            
            return NormParseResult(
                name = fullName,
                version = "",
                year = null,
                type = NormType.NIST
            )
        }
        
        // Try IEC pattern
        IEC_PATTERN.find(normString)?.let { match ->
            val baseName = normalizeIecName(match.groupValues[1])
            val year = match.groupValues[2].takeIf { it.isNotEmpty() }?.toIntOrNull()
            val section = match.groupValues[3].takeIf { it.isNotEmpty() }
            
            val fullName = if (section != null) {
                "$baseName $section"  // IEC uses space, not colon
            } else {
                baseName
            }
            
            val version = year?.toString() ?: ""
            
            return NormParseResult(
                name = fullName,
                version = version,
                year = if (isValidYear(year)) year else null,
                type = NormType.IEC
            )
        }
        
        return null
    }
    
    /**
     * Normalize ISO name format (ensure space between ISO and number)
     */
    private fun normalizeIsoName(name: String): String {
        return name.replace(Regex("ISO\\s*"), "ISO ")
    }
    
    /**
     * Normalize NIST name format
     */
    private fun normalizeNistName(name: String): String {
        return name.replace(Regex("\\s+"), " ").trim()
    }
    
    /**
     * Normalize IEC name format
     */
    private fun normalizeIecName(name: String): String {
        return name.replace(Regex("\\s+"), " ").trim()
    }
    
    /**
     * Validate year is within acceptable range
     */
    private fun isValidYear(year: Int?): Boolean {
        return year != null && year in MIN_YEAR..MAX_YEAR
    }
    
    /**
     * Find existing norm or create new one
     */
    @Transactional
    internal open fun findOrCreateNorm(parseResult: NormParseResult): Norm {
        // Try to find existing norm by name and version
        val existingNorm = findExistingNorm(parseResult.name, parseResult.version)
        
        if (existingNorm != null) {
            log.debug("Found existing norm: {} (version: {})", parseResult.name, parseResult.version)
            return existingNorm
        }
        
        // Create new norm
        val newNorm = Norm(
            name = parseResult.name,
            version = parseResult.version,
            year = parseResult.year
        )
        
        val savedNorm = normRepository.save(newNorm)
        log.info("Created new norm: {} (version: {}, year: {})", 
            savedNorm.name, savedNorm.version, savedNorm.year)
        
        return savedNorm
    }
    
    /**
     * Find existing norm by name and version
     */
    private fun findExistingNorm(name: String, version: String): Norm? {
        return try {
            // First try exact name and version match
            normRepository.findByName(name).orElse(null)?.let { norm ->
                if (norm.version == version) {
                    return norm
                }
            }
            
            // If version doesn't match, look for name with empty version
            if (version.isNotEmpty()) {
                normRepository.findByName(name).orElse(null)?.let { norm ->
                    if (norm.version.isEmpty()) {
                        return norm
                    }
                }
            }
            
            null
        } catch (e: Exception) {
            log.warn("Error finding existing norm: {}", e.message)
            null
        }
    }
    
    /**
     * Data class for parsed norm components
     */
    internal data class NormParseResult(
        val name: String,
        val version: String,
        val year: Int?,
        val type: NormType
    )
    
    /**
     * Enum for norm types
     */
    internal enum class NormType {
        ISO, NIST, IEC
    }
}