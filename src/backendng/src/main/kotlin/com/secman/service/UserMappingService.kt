package com.secman.service

import com.secman.domain.User
import com.secman.domain.UserMapping
import com.secman.domain.MappingStatus
import com.secman.dto.*
import com.secman.event.UserCreatedEvent
import com.secman.repository.AssetRepository
import com.secman.repository.UserMappingRepository
import com.secman.repository.UserRepository
import com.secman.util.EmailAddressValidator
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
    private val ipAddressParser: IpAddressParser,
    private val mcpAccessCacheInvalidator: McpAccessibleAssetsCacheInvalidator
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
        // A new mapping changes which assets the user can reach via cloudAccountId /
        // adDomain / ipAddress paths. Drop the per-user MCP access cache so the
        // change is visible immediately instead of after the 5-minute TTL.
        mcpAccessCacheInvalidator.invalidate()
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
        // Same reasoning as createMapping — domain / aws-account / ip changes can
        // shift which assets the user can reach.
        mcpAccessCacheInvalidator.invalidate()
        return updated.toResponse()
    }

    /**
     * Delete a mapping *scoped to a user*, as addressed by
     * `DELETE /api/users/{userId}/mappings/{mappingId}`.
     *
     * The email check is a consistency guard, not an ownership one: `userId` comes from the
     * path and names whose mapping is meant, so a mappingId belonging to somebody else must
     * be rejected rather than silently deleted through the wrong user's URL. Callers that
     * address a mapping by id alone want [deleteMappingById] instead — passing the caller's
     * own id here compares two unrelated things and fails for every mapping but their own.
     */
    @Transactional
    open fun deleteMapping(userId: Long, mappingId: Long): Boolean {
        val user = userRepository.findById(userId)
            .orElseThrow { NoSuchElementException("User not found") }

        val mapping = userMappingRepository.findById(mappingId)
            .orElseThrow { NoSuchElementException("Mapping not found") }

        if (mapping.email != user.email) {
            throw IllegalArgumentException("Mapping does not belong to user")
        }

        return deleteMappingRow(mapping)
    }

    /**
     * Delete a mapping addressed by id alone — the admin surface behind
     * `DELETE /api/user-mappings/{id}` (`@Secured("ADMIN")`).
     *
     * No email comparison: an admin manages everyone's mappings, and the authorization
     * decision is the controller's `@Secured("ADMIN")`. Mappings also exist for addresses
     * that have no user account at all (future/pending mappings created by an import), so
     * there is not always an owner to compare against.
     */
    @Transactional
    open fun deleteMappingById(mappingId: Long): Boolean {
        val mapping = userMappingRepository.findById(mappingId)
            .orElseThrow { NoSuchElementException("Mapping not found") }

        return deleteMappingRow(mapping)
    }

    private fun deleteMappingRow(mapping: UserMapping): Boolean {
        userMappingRepository.delete(mapping)
        // Revoking a mapping must take effect immediately — leaving stale entries
        // in the per-user MCP access cache would let removed accounts/domains
        // still drive asset visibility for up to 5 minutes.
        mcpAccessCacheInvalidator.invalidate()
        return true
    }

    // Validation regex patterns (matching CLI patterns)
    //
    // The email pattern is deliberately stricter than "one @ somewhere". A mapped email is
    // not just a database value: it becomes an SMTP recipient (the AWS-account risk
    // assessment start mail and its reminders), it is interpolated into log lines, and it is
    // written into the assessment's notes. The older `[^@]+@[^@]+\.[^@]+` accepted control
    // characters, spaces and commas anywhere outside the `@`, which is how CR/LF reaches a
    // log line (forging) and how a comma turns one `InternetAddress.parse` argument into two
    // recipients. The pattern lives in EmailAddressValidator — one copy, because two copies
    // of a security control drift. `matchesPattern` rather than `isValidRecipient` so the
    // over-length case keeps its own, more useful error message below.
    private val awsAccountIdRegex = Regex("^\\d{12}$")
    private val domainRegex = Regex("^[a-zA-Z0-9.-]+$")

    companion object {
        const val MAX_BULK_ENTRIES = 100_000

        /**
         * Longest accepted email address — the width of `user_mapping.email` and of
         * `aws_account_risk_assessment.owner_email`. Rejecting here turns what would be a
         * post-commit `DataException` (mappings already imported, every assessment for the
         * pair failing) into one ordinary per-row validation error.
         */
        const val MAX_EMAIL_LENGTH = 255

        /** Matches the aws_account_name column width (V260). */
        const val MAX_ACCOUNT_NAME_LENGTH = 255

        private const val MAX_ECHOED_VALUE_LENGTH = 80

        /**
         * Make a rejected value safe to echo back in an error message.
         *
         * These messages travel into `errors[]`, the CLI's stdout and the server log, so a
         * value that failed validation must not be able to inject line breaks (log forging)
         * or flood the response. Control characters become `?`; the rest is truncated.
         */
        fun sanitizeForMessage(value: String): String =
            value.take(MAX_ECHOED_VALUE_LENGTH)
                .map { if (it.isISOControl()) '?' else it }
                .joinToString("")
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
            if (!EmailAddressValidator.matchesPattern(trimmedEmail)) {
                errors.add("Entry ${index + 1}: Invalid email format '${sanitizeForMessage(entry.email)}'")
                return@forEachIndexed
            }
            if (trimmedEmail.length > MAX_EMAIL_LENGTH) {
                errors.add("Entry ${index + 1}: Email exceeds $MAX_EMAIL_LENGTH characters")
                return@forEachIndexed
            }
            if (entry.awsAccountId.isNullOrBlank() && entry.domain.isNullOrBlank()) {
                errors.add("Entry ${index + 1}: At least one of awsAccountId or domain must be provided")
                return@forEachIndexed
            }
            if (entry.awsAccountId != null && !awsAccountIdRegex.matches(entry.awsAccountId.trim())) {
                errors.add(
                    "Entry ${index + 1}: Invalid AWS account ID " +
                        "'${sanitizeForMessage(entry.awsAccountId)}' (must be 12 digits)"
                )
                return@forEachIndexed
            }
            if (entry.domain != null && entry.domain.isNotBlank() && !domainRegex.matches(entry.domain.trim())) {
                errors.add("Entry ${index + 1}: Invalid domain format '${sanitizeForMessage(entry.domain)}'")
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
                ),
                newAccounts = computeNewAccounts(validEntries)
            )
        }

        // Detect brand-new (DB-wide) AWS accounts BEFORE inserting, so the
        // existence query reflects pre-import state.
        val newAccounts = computeNewAccounts(validEntries)

        // Non-dry-run: create mappings.
        // Defense in depth: dedupe the *input list itself* on the same key the
        // unique constraint uses, so a single import containing repeated rows
        // (common with Cloud Custodian JSON listing the same account multiple
        // times under regions/envs) collapses to one save call. Without this,
        // the only thing standing between us and duplicates is the in-transaction
        // existsBy check, which is fragile if the persistence context skips an
        // auto-flush between iterations.
        val seenKeys = mutableSetOf<Triple<String, String?, String?>>()
        // (account -> display name) seen in this request. Applied in one statement per
        // account once the rows are in, so that mappings which already existed — the bulk
        // of a daily import — also carry the name. Without that the correction path
        // (WorkgroupAccountLinkService.linkFromStoredMappings) would be blind to every
        // account whose mappings predate this feature.
        val accountNames = LinkedHashMap<String, String>()
        // An over-long name is reported once per account, not once per owner — the same
        // account can appear on dozens of rows and 40 identical error strings help nobody.
        val reportedLongNames = mutableSetOf<String>()
        validEntries.forEach { entry ->
            val email = entry.email.lowercase().trim()
            val awsAccountId = entry.awsAccountId?.trim()
            // Match the entity's @PrePersist sentinel coercion so the dedup
            // key here is the same one the unique constraint will see.
            val domain = UserMapping.normalizeNullSentinel(entry.domain?.trim()?.lowercase())

            // Over-long names are dropped rather than truncated (a truncated name would
            // resolve to the wrong workgroup) and rather than failing the entry (the
            // mapping itself is valid and useful without one). Said out loud in errors[].
            val rawDisplayName = entry.displayName?.trim()?.takeIf { it.isNotEmpty() }
            val displayName = if (rawDisplayName != null && rawDisplayName.length > MAX_ACCOUNT_NAME_LENGTH) {
                if (reportedLongNames.add(awsAccountId ?: "-")) {
                    errors.add(
                        "Display name for account ${awsAccountId ?: "-"} exceeds " +
                            "$MAX_ACCOUNT_NAME_LENGTH characters and was not stored"
                    )
                }
                null
            } else {
                rawDisplayName
            }

            if (awsAccountId != null && displayName != null) {
                accountNames[awsAccountId] = displayName
            }

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
                awsAccountName = displayName,
                domain = domain
            )
            mapping.status = status
            if (user != null) {
                mapping.appliedAt = Instant.now()
            }

            userMappingRepository.save(mapping)

            if (status == MappingStatus.ACTIVE) created++ else createdPending++
        }

        applyAccountNames(accountNames)

        // Bulk import can flip access for many users at once — clear once at the
        // end of the batch rather than per-row to keep the hot path cheap.
        if (created + createdPending > 0) {
            mcpAccessCacheInvalidator.invalidate()
        }

        return BulkUserMappingResponse(
            totalProcessed = request.mappings.size,
            created = created,
            createdPending = createdPending,
            skipped = skipped,
            errors = errors,
            comparison = null,
            newAccounts = newAccounts
        )
    }

    /**
     * Apply the display names this request carried to every mapping of each account.
     *
     * Best-effort by design: the import's real work (the new mappings) has value on its
     * own, so a failure to write one name is logged and the import continues.
     */
    private fun applyAccountNames(accountNames: Map<String, String>) {
        if (accountNames.isEmpty()) return
        val now = Instant.now()
        var updated = 0
        accountNames.forEach { (awsAccountId, displayName) ->
            try {
                updated += userMappingRepository.updateAwsAccountName(awsAccountId, displayName, now)
            } catch (e: Exception) {
                log.warn(
                    "Could not set AWS account display name for {}: {}",
                    awsAccountId, e.message
                )
            }
        }
        if (updated > 0) {
            log.debug("Set AWS account display name on {} mapping row(s)", updated)
        }
    }

    /**
     * Compute brand-new (DB-wide) AWS accounts among [validEntries]: account IDs
     * present on no existing mapping. Returns each new account ID with the
     * distinct sorted emails it is mapped to within this request. Sorted by id.
     */
    private fun computeNewAccounts(validEntries: List<BulkUserMappingEntry>): List<NewAccountImportInfo> {
        val requestedIds = validEntries
            .mapNotNull { it.awsAccountId?.trim() }
            .filter { it.isNotBlank() }
            .toSet()
        if (requestedIds.isEmpty()) return emptyList()

        val preexisting = userMappingRepository.findExistingAwsAccountIds(requestedIds).toSet()
        val newIds = requestedIds - preexisting
        if (newIds.isEmpty()) return emptyList()

        return newIds.sorted().map { acctId ->
            val emails = validEntries
                .filter { it.awsAccountId?.trim() == acctId }
                .map { it.email.lowercase().trim() }
                .distinct()
                .sorted()
            NewAccountImportInfo(awsAccountId = acctId, emails = emails)
        }
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

        // Re-load the user inside this transaction instead of trusting the detached instance
        // from the event. The @Async listener races the publisher: if a (future) transactional
        // caller publishes UserCreatedEvent before its own commit, the user row is not yet
        // visible here (READ COMMITTED) — linking mappings to the detached instance would then
        // either violate the FK or point at a row that may still be rolled back. Skipping is
        // safe: the mapping stays unapplied and is picked up on the next relevant event.
        val managedUser = user.id?.let { userRepository.findById(it).orElse(null) }
        if (managedUser == null) {
            log.warn("applyFutureUserMapping: user id=${user.id} email=${user.email} not visible yet " +
                "(publisher transaction not committed?) - skipping mapping application")
            return 0
        }

        // Find future user mappings (case-insensitive email match, no user reference, not yet applied)
        val futureMappings = userMappingRepository.findByEmail(managedUser.email)
            .filter { it.user == null && it.appliedAt == null }

        if (futureMappings.isEmpty()) {
            log.debug("No future user mappings found for email: ${managedUser.email}")
            return 0
        }

        log.info("Found ${futureMappings.size} future user mapping(s) for email: ${managedUser.email}")

        for (futureMapping in futureMappings) {
            // Check for conflicting pre-existing mapping (mapping with user reference for same composite key)
            val hasConflict = if (futureMapping.ipAddress != null) {
                userMappingRepository.existsByEmailAndIpAddressAndDomain(
                    managedUser.email, futureMapping.ipAddress, futureMapping.domain
                ) && userMappingRepository.findByEmailAndIpAddressAndDomain(
                    managedUser.email, futureMapping.ipAddress, futureMapping.domain
                ).map { it.user != null }.orElse(false)
            } else {
                userMappingRepository.existsByEmailAndAwsAccountIdAndDomain(
                    managedUser.email, futureMapping.awsAccountId, futureMapping.domain
                ) && userMappingRepository.findByEmailAndAwsAccountIdAndDomain(
                    managedUser.email, futureMapping.awsAccountId, futureMapping.domain
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
            futureMapping.user = managedUser
            futureMapping.appliedAt = Instant.now()
            userMappingRepository.update(futureMapping)

            log.info("Applied future user mapping id=${futureMapping.id} to user ${managedUser.email}")
            appliedCount++
        }

        log.info("Applied $appliedCount future user mapping(s) to user ${managedUser.email}")
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
