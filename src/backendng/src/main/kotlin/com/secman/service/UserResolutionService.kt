package com.secman.service

import com.secman.domain.User
import com.secman.repository.UserRepository
import io.micronaut.serde.annotation.Serdeable
import jakarta.inject.Singleton
import jakarta.transaction.Transactional
import org.hibernate.exception.ConstraintViolationException
import org.slf4j.LoggerFactory
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import java.util.UUID

/**
 * Resolves a user by id or email, lazy-creating a real User row when an
 * email is given but no matching User exists yet (a "pending" user known
 * only via PENDING UserMapping rows).
 *
 * Extracted from AwsAccountSharingService.resolveUser so all save paths
 * (workgroup membership, risk assessment assignees, AWS sharing) share
 * the same materialization behavior.
 *
 * IMPORTANT: When a new User is created here we apply pending UserMappings
 * SYNCHRONOUSLY in the current transaction. We deliberately do NOT publish
 * UserCreatedEvent — see the inline comment for the Hibernate cross-session
 * collection-ownership reason.
 */
@Singleton
open class UserResolutionService(
    private val userRepository: UserRepository,
    private val userMappingService: UserMappingService
) {
    private val log = LoggerFactory.getLogger(UserResolutionService::class.java)
    private val passwordEncoder = BCryptPasswordEncoder()

    @Serdeable
    data class UserRef(val id: Long? = null, val email: String? = null)

    /**
     * @param userId  primary key — wins over email when both provided
     * @param email   case-insensitive email; required if userId is null/0
     * @param context short label used only in error messages
     */
    @Transactional
    open fun resolveByIdOrEmail(userId: Long?, email: String?, context: String): User {
        if (userId != null && userId > 0) {
            return userRepository.findById(userId)
                .orElseThrow { NoSuchElementException("$context user not found: id=$userId") }
        }
        val normalizedEmail = email?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw IllegalArgumentException("$context user identifier required (id or email)")

        userRepository.findByEmailIgnoreCase(normalizedEmail).orElse(null)?.let { return it }

        log.info("Creating User row on demand for {} email: {}", context, normalizedEmail)
        val username = resolveUniqueUsername(normalizedEmail.substringBefore("@").ifBlank { normalizedEmail })
        val newUser = User(
            username = username,
            email = normalizedEmail,
            passwordHash = passwordEncoder.encode(UUID.randomUUID().toString())!!,
            roles = mutableSetOf(User.Role.USER, User.Role.VULN, User.Role.REQ),
            authSource = User.AuthSource.OAUTH
        )
        val saved = try {
            userRepository.save(newUser)
        } catch (e: Exception) {
            // Another request may have created the same email between our check and our insert.
            // Walk the cause chain — Hibernate wraps unique-constraint hits as
            // org.hibernate.exception.ConstraintViolationException nested inside
            // PersistenceException / RollbackException depending on call site.
            val isUniqueRace = generateSequence<Throwable>(e) { it.cause }
                .any { it is ConstraintViolationException }
            if (!isUniqueRace) throw e
            log.info("Race on lazy User create for {} — re-querying", normalizedEmail)
            return userRepository.findByEmailIgnoreCase(normalizedEmail).orElseThrow {
                IllegalStateException("Lazy create failed and email still missing: $normalizedEmail", e)
            }
        }

        // Apply PENDING UserMappings synchronously in this transaction.
        //
        // We deliberately do NOT publish UserCreatedEvent: the default listener
        // (UserMappingService.onUserCreated) is @Async @Transactional, which opens
        // a SEPARATE Hibernate session while ours still owns the new User's
        // `workgroups` PersistentSet. That cross-session collection ownership
        // trips Hibernate's "Found shared references to a collection" check.
        try {
            val applied = userMappingService.applyFutureUserMapping(saved)
            if (applied > 0) {
                log.info("Applied {} pending mapping(s) to lazily-created user: {}", applied, saved.email)
            }
        } catch (e: Exception) {
            log.warn("Failed to apply pending mappings for {}: {}", saved.email, e.message)
        }
        return saved
    }

    @Transactional
    open fun resolveAll(refs: List<UserRef>, context: String): List<User> =
        refs.map { resolveByIdOrEmail(it.id, it.email, context) }

    /**
     * Append a numeric suffix when the email-prefix username is already taken.
     * Mirrors the existing AwsAccountSharingService.resolveUniqueUsername helper.
     */
    private fun resolveUniqueUsername(base: String): String {
        if (userRepository.findByUsername(base).isEmpty) return base
        var n = 2
        while (userRepository.findByUsername("$base$n").isPresent) n++
        return "$base$n"
    }
}
