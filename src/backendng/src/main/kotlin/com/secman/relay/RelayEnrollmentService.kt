package com.secman.relay

import com.secman.domain.RelayIdentity
import com.secman.repository.UserRepository
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Issues device enrollment codes and revocations, and pushes them to the relay.
 *
 * Two properties are worth stating plainly, because they are what make a relay
 * compromise survivable:
 *
 *  - **secman never stores the plaintext code.** It is generated, hashed,
 *    pushed as a digest, returned once to the admin who asked for it, and
 *    dropped. There is no table to steal it from and no endpoint to re-read it.
 *  - **the relay only ever holds the digest.** It can verify a code a device
 *    presents; it cannot produce one. So an attacker who owns the relay cannot
 *    mint themselves a device.
 *
 * There is deliberately no entity and no migration here. A code lives for
 * minutes, and the durable device registry is the relay's — secman reads it
 * back over the ingest plane when an admin wants to see it.
 */
@Singleton
open class RelayEnrollmentService(
    private val properties: RelayProperties,
    private val publisher: RelayPublisher,
    private val principalService: RelayPrincipalService,
    private val userRepository: UserRepository
) {
    private val logger = LoggerFactory.getLogger(RelayEnrollmentService::class.java)
    private val random = SecureRandom()

    companion object {
        /**
         * Crockford base32 minus the ambiguous glyphs: no I, L, O or U. The
         * code is read off a screen and typed into a phone, so a character set
         * that cannot be misread is a usability *and* a support-load decision.
         */
        private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

        /** 20 characters over a 32-symbol alphabet ≈ 100 bits of entropy. */
        private const val CODE_LENGTH = 20
        private const val GROUP_SIZE = 5

        const val MIN_TTL_MINUTES = 1L
        const val MAX_TTL_MINUTES = 1440L // 24h — the relay refuses anything longer
        const val DEFAULT_TTL_MINUTES = 15L

        const val MAX_SUBJECT_LENGTH = 254
        const val MAX_LABEL_LENGTH = 64
        const val MAX_SCOPES = 32

        private val SCOPE_SECTION = Regex("^[a-z0-9]([a-z0-9-]*[a-z0-9])?$")
        const val SCOPE_ALL = "status:*"
    }

    /**
     * Creates an enrollment code and pushes the grant to the relay.
     *
     * @throws IllegalArgumentException on invalid input; the message is safe to
     *   return to the ADMIN caller.
     * @throws IllegalStateException when the relay rejected or could not receive
     *   the grant — in which case no usable code exists and none is returned.
     */
    open fun createEnrollment(request: CreateRelayEnrollmentRequest, actor: String): CreateRelayEnrollmentResponse {
        val subject = validateSubject(request.subject)
        val scopes = validateScopes(request.scopes)
        val label = request.label?.let { validateLabel(it) }
        val ttlMinutes = validateTtl(request.ttlMinutes)

        val code = generateCode()
        val expiresAt = Instant.now().plus(ttlMinutes, ChronoUnit.MINUTES)

        // The grant names a principal; the relay refuses one it does not know.
        // Publishing the principal list in the same document (with the new
        // subject forced in, even if they have no linked identity yet) removes
        // an ordering hazard that would otherwise reject every first code.
        val principals = principalService.buildPrincipals(extraSubjects = setOf(subject))

        val control = RelayControl(
            instanceId = properties.instanceId,
            issuedAt = RelaySnapshotBuilder.rfc3339(Instant.now()),
            principalsAuthoritative = principals.isNotEmpty(),
            principals = principals,
            enrollments = listOf(
                RelayEnrollmentGrant(
                    codeSha256 = sha256Hex(code),
                    subject = subject,
                    scopes = scopes,
                    expiresAt = RelaySnapshotBuilder.rfc3339(expiresAt),
                    label = label
                )
            )
        )

        publisher.publishControl(control)?.let { error ->
            // Fail loudly rather than handing back a code the relay will not
            // honour: a code that silently does not work is a support incident
            // that looks like an app bug.
            logger.warn(
                "Relay enrollment could not be issued: actor={} subject={} outcome=failed reason={}",
                sanitizeForLog(actor), sanitizeForLog(subject), sanitizeForLog(error)
            )
            throw IllegalStateException("The relay did not accept the enrollment grant: $error")
        }

        // The code itself is never logged — only that one was issued, for whom,
        // by whom, and with which scopes (A09: actor + target + outcome).
        logger.info(
            "Relay enrollment issued: actor={} subject={} scopes={} ttlMinutes={} outcome=issued",
            sanitizeForLog(actor), sanitizeForLog(subject), scopes, ttlMinutes
        )

        return CreateRelayEnrollmentResponse(
            code = code,
            subject = subject,
            scopes = scopes,
            expiresAt = RelaySnapshotBuilder.rfc3339(expiresAt)
        )
    }

    /**
     * Revokes one device, or every device.
     *
     * @throws IllegalArgumentException on invalid input
     * @throws IllegalStateException when the relay could not be reached
     */
    open fun revoke(request: CreateRelayRevocationRequest, actor: String) {
        val deviceId = request.deviceId?.trim()
        if (!request.revokeAll && deviceId.isNullOrEmpty()) {
            throw IllegalArgumentException("Either deviceId or revokeAll is required")
        }
        if (deviceId != null && deviceId.length > 128) {
            throw IllegalArgumentException("deviceId is too long")
        }
        val reason = request.reason?.let { validateLabel(it) }

        val control = RelayControl(
            instanceId = properties.instanceId,
            issuedAt = RelaySnapshotBuilder.rfc3339(Instant.now()),
            revocations = listOf(
                RelayRevocation(
                    deviceId = if (request.revokeAll) null else deviceId,
                    revokeAll = request.revokeAll,
                    revokedAt = RelaySnapshotBuilder.rfc3339(Instant.now()),
                    reason = reason
                )
            )
        )

        publisher.publishControl(control)?.let { error ->
            logger.warn(
                "Relay revocation failed: actor={} deviceId={} revokeAll={} outcome=failed reason={}",
                sanitizeForLog(actor), sanitizeForLog(deviceId ?: "-"), request.revokeAll, sanitizeForLog(error)
            )
            throw IllegalStateException("The relay did not accept the revocation: $error")
        }

        logger.info(
            "Relay revocation applied: actor={} deviceId={} revokeAll={} outcome=revoked",
            sanitizeForLog(actor), sanitizeForLog(deviceId ?: "-"), request.revokeAll
        )
    }

    /** Generates a grouped, human-typeable code such as `7K2QX-3MNPB-...`. */
    internal fun generateCode(): String {
        val chars = CharArray(CODE_LENGTH)
        for (i in 0 until CODE_LENGTH) {
            // SecureRandom.nextInt(bound) is unbiased for any bound; the
            // alphabet is a power of two anyway.
            chars[i] = ALPHABET[random.nextInt(ALPHABET.length)]
        }
        return String(chars).chunked(GROUP_SIZE).joinToString("-")
    }

    internal fun sha256Hex(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        val out = StringBuilder(digest.size * 2)
        for (b in digest) out.append("%02x".format(b))
        return out.toString()
    }

    /**
     * Validates the enrollment subject.
     *
     * Two checks beyond syntax, both of which would otherwise surface as an
     * opaque 403 in the app rather than as an answerable error here:
     *
     *  - the subject must be a real secman user, because the relay resolves the
     *    grant against the principal list and refuses an unknown one;
     *  - the user must not hold a privileged role, because an admin may only be
     *    bound through a strong identity provider. The relay enforces that
     *    independently; this copy exists so the admin is told at issue time.
     */
    private fun validateSubject(raw: String): String {
        val subject = raw.trim()
        if (subject.isEmpty()) throw IllegalArgumentException("subject is required")
        if (subject.length > MAX_SUBJECT_LENGTH) {
            throw IllegalArgumentException("subject must be at most $MAX_SUBJECT_LENGTH characters")
        }
        // The subject ends up in the relay's registry, in its log and in
        // secman's; reject the control characters that would let it forge a
        // record in any of the three.
        if (subject.any { it.isISOControl() }) {
            throw IllegalArgumentException("subject must not contain control characters")
        }

        val user = userRepository.findByUsername(subject).orElse(null)
            ?: throw IllegalArgumentException("No secman user named '${sanitizeForLog(subject)}'")
        if (user.roles.any { it.name == "ADMIN" }) {
            throw IllegalArgumentException(
                "'${sanitizeForLog(subject)}' holds ADMIN and may only sign in with " +
                    "${RelayIdentity.Provider.STRONG.sorted()}; an enrollment code will be refused by the relay"
            )
        }
        return subject
    }

    private fun validateLabel(raw: String): String {
        val label = raw.trim()
        if (label.length > MAX_LABEL_LENGTH) {
            throw IllegalArgumentException("label must be at most $MAX_LABEL_LENGTH characters")
        }
        if (label.any { it.isISOControl() }) {
            throw IllegalArgumentException("label must not contain control characters")
        }
        return label
    }

    /**
     * Validates the requested scopes against a closed grammar.
     *
     * Deny by default: an unrecognised scope string is rejected here rather than
     * quietly stored and then silently ignored by the relay, which would leave
     * an admin believing they had granted something they had not.
     */
    private fun validateScopes(raw: List<String>): List<String> {
        if (raw.isEmpty()) throw IllegalArgumentException("at least one scope is required")
        if (raw.size > MAX_SCOPES) throw IllegalArgumentException("at most $MAX_SCOPES scopes are allowed")

        val validated = raw.map { it.trim() }.map { scope ->
            if (scope == SCOPE_ALL) return@map scope
            val section = scope.removePrefix("status:")
            if (section == scope) {
                throw IllegalArgumentException("scope must be \"$SCOPE_ALL\" or \"status:<section>\" (got \"${sanitizeForLog(scope)}\")")
            }
            if (!SCOPE_SECTION.matches(section)) {
                throw IllegalArgumentException("scope section must be lowercase letters, digits and '-' (got \"${sanitizeForLog(section)}\")")
            }
            if (section !in RelaySnapshotBuilder.ALL_SECTIONS) {
                throw IllegalArgumentException("unknown section \"${sanitizeForLog(section)}\"; valid sections: ${RelaySnapshotBuilder.ALL_SECTIONS}")
            }
            scope
        }
        return validated.distinct()
    }

    private fun validateTtl(requested: Long?): Long {
        val ttl = requested ?: DEFAULT_TTL_MINUTES
        if (ttl < MIN_TTL_MINUTES || ttl > MAX_TTL_MINUTES) {
            throw IllegalArgumentException("ttlMinutes must be between $MIN_TTL_MINUTES and $MAX_TTL_MINUTES")
        }
        return ttl
    }
}
