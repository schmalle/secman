package services;

import models.Norm;
import play.db.jpa.JPAApi;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.persistence.NoResultException;
import jakarta.persistence.TypedQuery;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.ArrayList;

@Singleton
public class NormParsingService {
    
    private final JPAApi jpaApi;
    private static final play.Logger.ALogger logger = play.Logger.of(NormParsingService.class);
    
    // Regex patterns for different norm formats
    // Updated to handle formats like "ISO 27001: A.8.1.1", "NIST SP 800-171: 3.4.1", "IEC62443-2-1 S2.3.3.6"
    private static final Pattern ISO_PATTERN = Pattern.compile("^(ISO\\s*\\d+)(?::(\\d{4}))?(?:[:\\s]+(.+))?", Pattern.CASE_INSENSITIVE);
    private static final Pattern NIST_PATTERN = Pattern.compile("^(NIST\\s+[A-Z]+(?:\\s+[\\d\\w-]+)*)(?:\\s*:\\s*(.+))?", Pattern.CASE_INSENSITIVE);
    private static final Pattern IEC_PATTERN = Pattern.compile("^(IEC\\s*\\d+(?:-\\d+)*(?:-\\d+)?)(?::(\\d{4}))?(?:[:\\s]+(.+))?", Pattern.CASE_INSENSITIVE);
    private static final Pattern YEAR_PATTERN = Pattern.compile("(\\d{4})");
    
    @Inject
    public NormParsingService(JPAApi jpaApi) {
        this.jpaApi = jpaApi;
    }
    
    /**
     * Parse a norm string and return or create a Norm entity
     * Examples: "ISO 27001:2013 A.8.1.1", "NIST CSF PR.AC-1", "IEC 62443-3-3:2013"
     */
    public Norm parseAndCreateNorm(String normString) {
        if (normString == null || normString.trim().isEmpty()) {
            return null;
        }
        
        normString = normString.trim();
        logger.debug("Parsing norm string: {}", normString);
        
        // Try to parse the norm string - returns null if parsing fails
        ParsedNorm parsed = parseNormString(normString);
        
        // Return null if parsing failed (no valid NIST/ISO/IEC format found)
        if (parsed == null) {
            logger.warn("Failed to parse norm string '{}' - not a valid NIST/ISO/IEC format", normString);
            return null;
        }
        
        // Find existing norm or create new one
        return jpaApi.withTransaction(em -> {
            try {
                // Try to find existing norm by name and version
                TypedQuery<Norm> query = em.createQuery(
                    "SELECT n FROM Norm n WHERE n.name = :name AND n.version = :version", 
                    Norm.class
                );
                query.setParameter("name", parsed.name);
                query.setParameter("version", parsed.version);
                
                Norm existingNorm = query.getSingleResult();
                logger.debug("Found existing norm: {} {}", existingNorm.getName(), existingNorm.getVersion());
                return existingNorm;
                
            } catch (NoResultException e) {
                // Create new norm
                Norm newNorm = new Norm();
                newNorm.setName(parsed.name);
                newNorm.setVersion(parsed.version);
                newNorm.setYear(parsed.year);
                
                em.persist(newNorm);
                logger.info("Created new norm: {} {} ({})", newNorm.getName(), newNorm.getVersion(), newNorm.getYear());
                return newNorm;
            }
        });
    }
    
    /**
     * Parse multiple norm strings from import data
     */
    public Set<Norm> parseAndCreateNorms(List<String> normStrings) {
        Set<Norm> norms = new HashSet<>();
        
        for (String normString : normStrings) {
            if (normString != null && !normString.trim().isEmpty()) {
                Set<Norm> parsedNorms = parseAndCreateNormsFromSingleString(normString);
                norms.addAll(parsedNorms);
            }
        }
        
        return norms;
    }
    
    /**
     * Parse multiple norms from a single string containing separators
     * Handles •, ;, and , (comma+space) as norm separators as specified in requirements
     * Only accepts norms with NIST, ISO, or IEC prefixes. Invalid norms are skipped.
     */
    public Set<Norm> parseAndCreateNormsFromSingleString(String normString) {
        Set<Norm> norms = new HashSet<>();
        
        if (normString == null || normString.trim().isEmpty()) {
            return norms;
        }
        
        logger.debug("Parsing multiple norms from string: {}", normString);
        
        // Split by • (bullet), ; (semicolon), and , (comma) as specified by user requirements
        // Enhanced to handle "NORM1, NORM2" construct with comma+space separator
        // Handle whitespace around separators: "ISO 27001 • NIST CSF ; IEC 62443, NIST SP 800-53"
        String[] splitNorms = normString.split("\\s*[•;,|]\\s*");
        
        for (String singleNorm : splitNorms) {
            String trimmedNorm = singleNorm.trim();
            // Strip trailing periods that might be present in Excel
            if (trimmedNorm.endsWith(".")) {
                trimmedNorm = trimmedNorm.substring(0, trimmedNorm.length() - 1).trim();
            }
            
            if (!trimmedNorm.isEmpty()) {
                try {
                    // Only parse if it matches valid norm patterns (NIST/ISO/IEC)
                    if (isValidNormFormat(trimmedNorm)) {
                        Norm norm = parseAndCreateNorm(trimmedNorm);
                        if (norm != null) {
                            norms.add(norm);
                            logger.debug("Successfully parsed norm: {} {} from segment: {}", 
                                norm.getName(), norm.getVersion(), trimmedNorm);
                        } else {
                            logger.warn("Failed to create norm from valid format: '{}'", trimmedNorm);
                        }
                    } else {
                        logger.warn("Skipping invalid norm format (not NIST/ISO/IEC): '{}'", trimmedNorm);
                    }
                } catch (Exception e) {
                    logger.warn("Failed to parse norm segment '{}' from string '{}': {}", 
                        trimmedNorm, normString, e.getMessage());
                    // Continue with other norms even if one fails
                }
            }
        }
        
        logger.info("Parsed {} unique norms from string: {} (from {} segments)", 
            norms.size(), normString, splitNorms.length);
        return norms;
    }
    
    /**
     * Check if a norm string matches valid NIST/ISO/IEC format
     */
    private boolean isValidNormFormat(String normString) {
        if (normString == null || normString.trim().isEmpty()) {
            return false;
        }
        
        // Check if it matches any of the valid patterns
        return ISO_PATTERN.matcher(normString).find() || 
               NIST_PATTERN.matcher(normString).find() || 
               IEC_PATTERN.matcher(normString).find();
    }
    
    /**
     * Internal method to parse norm string into components
     * Only accepts NIST/ISO/IEC norms. Returns null if parsing fails.
     */
    private ParsedNorm parseNormString(String normString) {
        ParsedNorm result = new ParsedNorm();
        
        // Try ISO pattern first
        Matcher isoMatcher = ISO_PATTERN.matcher(normString);
        if (isoMatcher.find()) {
            String baseName = isoMatcher.group(1); // "ISO27001" or "ISO 27001"
            String year = isoMatcher.group(2); // "2013" or null
            String section = isoMatcher.group(3) != null ? isoMatcher.group(3).trim() : ""; // "A.8.1.1"
            
            // Normalize baseName to ensure consistent spacing: "ISO27001" -> "ISO 27001"
            baseName = baseName.replaceAll("^(ISO)\\s*(\\d+)", "$1 $2").trim();
            
            // Build full norm name including section
            StringBuilder fullName = new StringBuilder(baseName);
            if (year != null && !year.isEmpty()) {
                fullName.append(":").append(year);
            }
            if (!section.isEmpty()) {
                fullName.append(": ").append(section);
            }
            
            result.name = fullName.toString();
            result.version = year != null ? year : "";
            result.year = extractYear(year);
            result.section = section;
            return result;
        }
        
        // Try NIST pattern
        Matcher nistMatcher = NIST_PATTERN.matcher(normString);
        if (nistMatcher.find()) {
            String baseName = nistMatcher.group(1).replaceAll("\\s+", " ").trim(); // "NIST SP 800-171"
            String section = nistMatcher.group(2) != null ? nistMatcher.group(2).trim() : ""; // "3.4.1"
            
            // Build full norm name including section
            StringBuilder fullName = new StringBuilder(baseName);
            if (!section.isEmpty()) {
                fullName.append(": ").append(section); // Add colon-space separator
            }
            
            result.name = fullName.toString();
            result.version = "";
            result.year = extractYearFromString(normString);
            result.section = section;
            return result;
        }
        
        // Try IEC pattern
        Matcher iecMatcher = IEC_PATTERN.matcher(normString);
        if (iecMatcher.find()) {
            String baseName = iecMatcher.group(1); // "IEC62443-2-1" or "IEC 62443-3-3"
            String year = iecMatcher.group(2); // "2013" or null
            String section = iecMatcher.group(3) != null ? iecMatcher.group(3).trim() : ""; // "S2.3.3.6"
            
            // Normalize baseName spacing: preserve existing spacing but clean up extra spaces
            baseName = baseName.replaceAll("\\s+", " ").trim();
            
            // Strip trailing periods from section
            if (section.endsWith(".")) {
                section = section.substring(0, section.length() - 1);
            }
            
            // Build full norm name including section
            StringBuilder fullName = new StringBuilder(baseName);
            if (year != null && !year.isEmpty()) {
                fullName.append(":").append(year);
            }
            if (!section.isEmpty()) {
                fullName.append(" ").append(section); // IEC uses space instead of colon
            }
            
            result.name = fullName.toString();
            result.version = year != null ? year : "";
            result.year = extractYear(year);
            result.section = section;
            return result;
        }
        
        // No fallback - return null if no valid pattern matches
        // This ensures only NIST/ISO/IEC norms are accepted
        logger.warn("Norm string '{}' does not match any valid pattern (NIST/ISO/IEC)", normString);
        return null;
    }
    
    /**
     * Extract year from version string
     */
    private Integer extractYear(String versionString) {
        if (versionString == null || versionString.trim().isEmpty()) {
            return null;
        }
        
        try {
            int year = Integer.parseInt(versionString);
            // Validate year range (reasonable range for standards)
            if (year >= 1990 && year <= 2030) {
                return year;
            }
        } catch (NumberFormatException e) {
            // Not a valid year
        }
        
        return null;
    }
    
    /**
     * Extract year from any string using regex
     */
    private Integer extractYearFromString(String text) {
        if (text == null) return null;
        
        Matcher yearMatcher = YEAR_PATTERN.matcher(text);
        while (yearMatcher.find()) {
            try {
                int year = Integer.parseInt(yearMatcher.group(1));
                if (year >= 1990 && year <= 2030) {
                    return year;
                }
            } catch (NumberFormatException e) {
                // Continue searching
            }
        }
        
        return null;
    }
    
    /**
     * Get all existing norms for dropdown/selection purposes
     */
    public List<Norm> getAllNorms() {
        return jpaApi.withTransaction(em -> {
            TypedQuery<Norm> query = em.createQuery(
                "SELECT n FROM Norm n ORDER BY n.name, n.version", 
                Norm.class
            );
            return query.getResultList();
        });
    }
    
    /**
     * Inner class to hold parsed norm components
     */
    private static class ParsedNorm {
        String name = "";
        String version = "";
        Integer year = null;
        String section = "";
    }
}