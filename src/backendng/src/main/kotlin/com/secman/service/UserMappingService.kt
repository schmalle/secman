package com.secman.service

import com.secman.domain.User
import com.secman.domain.UserMapping
import com.secman.domain.MappingStatus
import com.secman.dto.*
import com.secman.event.UserCreatedEvent
import com.secman.repository.AssetRepository
import com.secman.repository.UserMappingRepository
import com.secman.repository.UserRepository
import io.micronaut.runtime.event.annotation.EventListener
import io.micronaut.scheduling.annotation.Async
import jakarta.inject.Singleton
import jakarta.transaction.Transactional
import org.slf4j.LoggerFactory
import java.time.Instant

@Singleton
open class UserMappingService(
    private val userRepository: UserRepository,
    private val userMappingRepository: UserMappingRepository,
    private val assetRepository: AssetRepository,
    private val ipAddressParser: IpAddressParser
) {
    private val log = LoggerFactory.getLogger(UserMappingService::class.java)
    
    fun getUserMappings(userId: Long): List<UserMappingResponse> {
        val user = userRepository.findById(userId)
            .orElseThrow { NoSuchElementException("User not found") }

        val mappings = userMappingRepository.findByEmail(user.email)
        return mappings.map { it.toResponse() }
    }

    fun getDistinctDomains(): List<String> {
        val mappingDomains = userMappingRepository.findDistinctDomains()
        val assetDomains = assetRepository.findDistinctAdDomains()
        return (mappingDomains + assetDomains)
            .map { it.lowercase() }
            .distinct()
            .sorted()
    }
    
    @Transactional
    open fun createMapping(userId: Long, request: CreateUserMappingRequest): UserMappingResponse {
        // Validate at least one field (Feature 020: extended to include ipAddress)
        if (request.awsAccountId == null && request.domain == null && request.ipAddress == null) {
            throw IllegalArgumentException("At least one of Domain, AWS Account ID, or IP Address must be provided")
        }

        // Get user email
        val user = userRepository.findById(userId)
            .orElseThrow { NoSuchElementException("User not found") }

        // Parse IP address if provided (Feature 020)
        var ipParseResult: IpAddressParser.IpParseResult? = null
        if (request.ipAddress != null) {
            try {
                ipParseResult = ipAddressParser.parse(request.ipAddress)
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("Invalid IP address format: ${e.message}", e)
            }
        }

        // Canonicalize the domain so the existsBy check, the @PrePersist
        // callback, and the unique constraint all see the same key. Without
        // this, a UI add of "-none-" lands beside the existing real-NULL row.
        val normalizedDomain = UserMapping.normalizeNullSentinel(request.domain?.lowercase()?.trim())

        // Check for duplicates (extended for IP addresses - Feature 020)
        if (request.ipAddress != null) {
            if (userMappingRepository.existsByEmailAndIpAddressAndDomain(
                    user.email, request.ipAddress, normalizedDomain
                )) {
                throw IllegalStateException("IP mapping already exists for this email, IP address, and domain")
            }
        } else {
            if (userMappingRepository.existsByEmailAndAwsAccountIdAndDomain(
                    user.email, request.awsAccountId, normalizedDomain
                )) {
                throw IllegalStateException("AWS mapping already exists for this email, AWS account, and domain")
            }
        }

        // Create and save
        val mapping = UserMapping(
            email = user.email,
            awsAccountId = request.awsAccountId,
            domain = normalizedDomain
        )

        // Set IP fields if IP address was provided (Feature 020)
        if (ipParseResult != null) {
            mapping.ipAddress = request.ipAddress
            mapping.ipRangeType = ipParseResult.rangeType
            mapping.ipRangeStart = ipParseResult.startIpNumeric
            mapping.ipRangeEnd = ipParseResult.endIpNumeric
        }

        val savedMapping = userMappingRepository.save(mapping)
        return savedMapping.toResponse()
    }
    
    @Transactional
    open fun updateMapping(userId: Long, mappingId: Long, request: UpdateUserMappingRequest): UserMappingResponse {
        // Validate at least one field (Feature 020: extended to include ipAddress)
        if (request.awsAccountId == null && request.domain == null && request.ipAddress == null) {
            throw IllegalArgumentException("At least one of Domain, AWS Account ID, or IP Address must be provided")
        }

        // Get user
        val user = userRepository.findById(userId)
            .orElseThrow { NoSuchElementException("User not found") }

        // Get mapping and verify ownership
        val mapping = userMappingRepository.findById(mappingId)
            .orElseThrow { NoSuchElementException("Mapping not found") }

        if (mapping.email != user.email) {
            throw IllegalArgumentException("Mapping does not belong to user")
        }

        // Parse IP address if provided (Feature 020)
        var ipParseResult: IpAddressParser.IpParseResult? = null
        if (request.ipAddress != null) {
            try {
                ipParseResult = ipAddressParser.parse(request.ipAddress)
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("Invalid IP address format: ${e.message}", e)
            }
        }

        // Canonicalize the domain — see createMapping for rationale.
        val normalizedRequestDomain = UserMapping.normalizeNullSentinel(request.domain?.lowercase()?.trim())

        // Check for duplicates (excluding current mapping) - Feature 020
        if (request.ipAddress != null) {
            val isDuplicate = userMappingRepository.existsByEmailAndIpAddressAndDomain(
                user.email, request.ipAddress, normalizedRequestDomain
            ) && (mapping.ipAddress != request.ipAddress || mapping.domain != normalizedRequestDomain)

            if (isDuplicate) {
                throw IllegalStateException("IP mapping already exists for this email, IP address, and domain")
            }
        } else {
            val isDuplicate = userMappingRepository.existsByEmailAndAwsAccountIdAndDomain(
                user.email, request.awsAccountId, normalizedRequestDomain
            ) && (mapping.awsAccountId != request.awsAccountId || mapping.domain != normalizedRequestDomain)

            if (isDuplicate) {
                throw IllegalStateException("AWS mapping already exists for this email, AWS account, and domain")
            }
        }

        // Update
        mapping.awsAccountId = request.awsAccountId
        mapping.domain = normalizedRequestDomain

        // Update IP fields if IP address was provided (Feature 020)
        if (ipParseResult != null) {
            mapping.ipAddress = request.ipAddress
            mapping.ipRangeType = ipParseResult.rangeType
            mapping.ipRangeStart = ipParseResult.startIpNumeric
            mapping.ipRangeEnd = ipParseResult.endIpNumeric
        } else {
            // Clear IP fields if no IP address provided
            mapping.ipAddress = null
            mapping.ipRangeType = null
            mapping.ipRangeStart = null
            mapping.ipRangeEnd = null
        }

        val updated = userMappingRepository.update(mapping)
        return updated.toResponse()
    }
    
    @Transactional
    open fun deleteMapping(userId: Long, mappingId: Long): Boolean {
        val user = userRepository.findById(userId)
            .orElseThrow { NoSuchElementException("User not found") }

        val mapping = userMappingRepository.findById(mappingId)
            .orElseThrow { NoSuchElementException("Mapping not found") }

        if (mapping.email != user.email) {
            throw IllegalArgumentException("Mapping does not belong to user")
        }

        userMappingRepository.delete(mapping)
        return true
    }

    // Validation regex patterns (matching CLI patterns)
    private val emailRegex = Regex("^[^@]+@[^@]+\\.[^@]+$")
    private val awsAccountIdRegex = Regex("^\\d{12}$")
    private val domainRegex = Regex("^[a-zA-Z0-9.-]+$")

    companion object {
        const val MAX_BULK_ENTRIES = 100_000
    }

    /**
     * Bulk create user mappings with optional dry-run comparison.
     *
     * For dryRun=false: validates, deduplicates, and saves each entry.
     * For dryRun=true: validates formats, then compares (email, awsAccountId) key sets
     * between the request and all existing DB mappings to produce new/unchanged/removed counts.
     */
    @Transactional
    open fun bulkCreateMappings(request: BulkUserMappingRequest): BulkUserMappingResponse {
        if (request.mappings.size > MAX_BULK_ENTRIES) {
            throw IllegalArgumentException(
                "Request contains ${request.mappings.size} entries, exceeding maximum of $MAX_BULK_ENTRIES"
            )
        }

        val errors = mutableListOf<String>()
        var created = 0
        var createdPending = 0
        var skipped = 0

        // Validate all entries first
        val validEntries = mutableListOf<BulkUserMappingEntry>()
        request.mappings.forEachIndexed { index, entry ->
            val trimmedEmail = entry.email.trim()
            if (trimmedEmail.isBlank() || !emailRegex.matches(trimmedEmail)) {
                errors.add("Entry ${index + 1}: Invalid email format '${entry.email}'")
                return@forEachIndexed
            }
            if (entry.awsAccountId != null && !awsAccountIdRegex.matches(entry.awsAccountId.trim())) {
                errors.add("Entry ${index + 1}: Invalid AWS account ID '${entry.awsAccountId}' (must be 12 digits)")
                return@forEachIndexed
            }
            if (entry.domain != null && entry.domain.isNotBlank() && !domainRegex.matches(entry.domain.trim())) {
                errors.add("Entry ${index + 1}: Invalid domain format '${entry.domain}'")
                return@forEachIndexed
            }
            validEntries.add(entry)
        }

        if (request.dryRun) {
            // Dry-run: compare (email, awsAccountId) key sets against DB
            val fileKeys = validEntries
                .filter { it.awsAccountId != null }
                .map { Pair(it.email.lowercase().trim(), it.awsAccountId!!.trim()) }
                .toSet()

            val dbKeys = userMappingRepository.findAll()
                .filter { it.awsAccountId != null }
                .map { Pair(it.email.lowercase().trim(), it.awsAccountId!!.trim()) }
                .toSet()

            val newKeys = fileKeys - dbKeys
            val unchangedKeys = fileKeys.intersect(dbKeys)
            val removedKeys = dbKeys - fileKeys

            return BulkUserMappingResponse(
                totalProcessed = validEntries.size,
                created = 0,
                createdPending = 0,
                skipped = 0,
                errors = errors,
                comparison = MappingComparisonResponse(
                    dbMappingCount = dbKeys.size,
                    fileMappingCount = fileKeys.size,
                    newCount = newKeys.size,
                    unchangedCount = unchangedKeys.size,
                    removedCount = removedKeys.size
                )
            )
        }

        // Non-dry-run: create mappings.
        // Defense in depth: dedupe the *input list itself* on the same key the
        // unique constraint uses, so a single import containing repeated rows
        // (common with Cloud Custodian JSON listing the same account multiple
        // times under regions/envs) collapses to one save call. Without this,
        // the only thing standing between us and duplicates is the in-transaction
        // existsBy check, which is fragile if the persistence context skips an
        // auto-flush between iterations.
        val seenKeys = mutableSetOf<Triple<String, String?, String?>>()
        validEntries.forEach { entry ->
            val email = entry.email.lowercase().trim()
            val awsAccountId = entry.awsAccountId?.trim()
            // Match the entity's @PrePersist sentinel coercion so the dedup
            // key here is the same one the unique constraint will see.
            val domain = UserMapping.normalizeNullSentinel(entry.domain?.trim()?.lowercase())

            val key = Triple(email, awsAccountId, domain)
            if (!seenKeys.add(key)) {
                skipped++
                return@forEach
            }

            // Duplicate check (NULL-safe — see UserMappingRepository @Query).
            val exists = userMappingRepository.existsByEmailAndAwsAccountIdAndDomain(email, awsAccountId, domain)
            if (exists) {
                skipped++
                return@forEach
            }

            // Resolve user for ACTIVE vs PENDING status
            val user = userRepository.findByEmailIgnoreCase(email).orElse(null)
            val status = if (user != null) MappingStatus.ACTIVE else MappingStatus.PENDING

            val mapping = UserMapping(
                email = email,
                user = user,
                awsAccountId = awsAccountId,
                domain = domain
            )
            mapping.status = status
            if (user != null) {
                mapping.appliedAt = Instant.now()
            }

            userMappingRepository.save(mapping)

            if (status == MappingStatus.ACTIVE) created++ else createdPending++
        }

        return BulkUserMappingResponse(
            totalProcessed = request.mappings.size,
            created = created,
            createdPending = createdPending,
            skipped = skipped,
            errors = errors,
            comparison = null
        )
    }

    // Feature 042: Future User Mapping Support

    /**
     * Event listener for user creation - automatically applies future user mappings
     *
     * When a new user is created (via OAuth auto-provisioning or manual creation),
     * this method checks for existing future user mappings (email-only mappings)
     * and applies them to the new user account.
     *
     * Feature 042: Future User Mappings
     *
     * @param event UserCreatedEvent containing the newly created user
     */
    @EventListener
    @Async
    open fun onUserCreated(event: UserCreatedEvent) {
        log.info("User created event received: email=${event.user.email}, source=${event.source}")

        try {
            applyFutureUserMapping(event.user)
        } catch (e: Exception) {
            log.error("Failed to apply future user mapping for user ${event.user.email}", e)
            // Don't throw - user creation should not fail if mapping application fails
        }
    }

    /**
     * Apply future user mapping to a newly created user
     *
     * Looks up any existing future user mappings (mappings with matching email but no user reference)
     * and applies them to the new user. Uses "pre-existing mapping wins" strategy for conflicts.
     *
     * Feature 042: Future User Mappings
     *
     * @param user The newly created user
     * @return Number of mappings applied
     */
    @Transactional
    open fun applyFutureUserMapping(user: User): Int {
        var appliedCount = 0

        // Find future user mappings (case-insensitive email match, no user reference, not yet applied)
        val futureMappings = userMappingRepository.findByEmail(user.email)
            .filter { it.user == null && it.appliedAt == null }

        if (futureMappings.isEmpty()) {
            log.debug("No future user mappings found for email: ${user.email}")
            return 0
        }

        log.info("Found ${futureMappings.size} future user mapping(s) for email: ${user.email}")

        for (futureMapping in futureMappings) {
            // Check for conflicting pre-existing mapping (mapping with user reference for same composite key)
            val hasConflict = if (futureMapping.ipAddress != null) {
                userMappingRepository.existsByEmailAndIpAddressAndDomain(
                    user.email, futureMapping.ipAddress, futureMapping.domain
                ) && userMappingRepository.findByEmailAndIpAddressAndDomain(
                    user.email, futureMapping.ipAddress, futureMapping.domain
                ).map { it.user != null }.orElse(false)
            } else {
                userMappingRepository.existsByEmailAndAwsAccountIdAndDomain(
                    user.email, futureMapping.awsAccountId, futureMapping.domain
                ) && userMappingRepository.findByEmailAndAwsAccountIdAndDomain(
                    user.email, futureMapping.awsAccountId, futureMapping.domain
                ).map { it.user != null }.orElse(false)
            }

            if (hasConflict) {
                log.warn("Pre-existing mapping conflicts with future mapping id=${futureMapping.id}, skipping application")
                // Mark as applied but don't link to user (conflict resolution strategy)
                futureMapping.appliedAt = Instant.now()
                userMappingRepository.update(futureMapping)
                continue
            }

            // Apply the future mapping to the user
            futureMapping.user = user
            futureMapping.appliedAt = Instant.now()
            userMappingRepository.update(futureMapping)

            log.info("Applied future user mapping id=${futureMapping.id} to user ${user.email}")
            appliedCount++
        }

        log.info("Applied $appliedCount future user mapping(s) to user ${user.email}")
        return appliedCount
    }

    /**
     * Get current mappings (future + active) with pagination
     *
     * Returns all mappings that have not yet been applied (appliedAt IS NULL).
     * This includes both future user mappings and active user mappings.
     *
     * Feature 042: Future User Mappings
     *
     * @param pageable Pagination parameters (page, size, sort)
     * @return Page of current mappings
     */
    fun getCurrentMappings(pageable: io.micronaut.data.model.Pageable): io.micronaut.data.model.Page<UserMappingResponse> {
        val page = userMappingRepository.findByAppliedAtIsNull(pageable)
        return page.map { it.toResponse() }
    }

    /**
     * Get applied historical mappings with pagination
     *
     * Returns all mappings that have been applied to users (appliedAt IS NOT NULL).
     *
     * Feature 042: Future User Mappings
     *
     * @param pageable Pagination parameters (page, size, sort)
     * @return Page of applied historical mappings
     */
    fun getAppliedHistory(pageable: io.micronaut.data.model.Pageable): io.micronaut.data.model.Page<UserMappingResponse> {
        val page = userMappingRepository.findByAppliedAtIsNotNull(pageable)
        return page.map { it.toResponse() }
    }

    /**
     * Count current mappings (future + active)
     *
     * Feature 042: Future User Mappings
     *
     * @return Number of current mappings (appliedAt IS NULL)
     */
    fun countCurrentMappings(): Long {
        return userMappingRepository.countByAppliedAtIsNull()
    }

    /**
     * Count applied historical mappings
     *
     * Feature 042: Future User Mappings
     *
     * @return Number of applied historical mappings (appliedAt IS NOT NULL)
     */
    fun countAppliedHistory(): Long {
        return userMappingRepository.countByAppliedAtIsNotNull()
    }
}
