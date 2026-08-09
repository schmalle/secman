package com.secman.relay

import com.secman.domain.RelayIdentity
import com.secman.repository.RelayIdentityRepository
import com.secman.repository.UserRepository
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory

/**
 * Builds the principal list the relay uses to answer "who is this, and what may
 * they see".
 *
 * This is the mechanism behind the requirement that the app carry the same
 * access rights as secman itself. secman is the only authority: it states each
 * user's current roles, and the relay enforces them against the per-section
 * policy in the snapshot. The relay has no user table of its own, no way to
 * grant a role, and no way to reach back and ask.
 *
 * **Only users who can actually use the app are published.** A principal is
 * included when it has at least one linked external identity or a pending
 * enrollment path — publishing the entire user directory to a DMZ box would
 * hand an attacker a staff list for nothing in return.
 */
@Singleton
open class RelayPrincipalService(
    private val userRepository: UserRepository,
    private val relayIdentityRepository: RelayIdentityRepository
) {
    private val logger = LoggerFactory.getLogger(RelayPrincipalService::class.java)

    /**
     * Builds the authoritative principal list.
     *
     * @param extraSubjects usernames to include even without a linked identity —
     *   used when an admin has just issued an enrollment code, so the grant has
     *   a principal to attach to.
     */
    open fun buildPrincipals(extraSubjects: Set<String> = emptySet()): List<RelayPrincipal> {
        val identitiesByUserId = HashMap<Long, MutableList<RelayIdentity>>()
        for (identity in relayIdentityRepository.findAll()) {
            identitiesByUserId.getOrPut(identity.userId) { mutableListOf() }.add(identity)
        }

        val principals = ArrayList<RelayPrincipal>()
        for (user in userRepository.findAll()) {
            val userId = user.id ?: continue
            val identities = identitiesByUserId[userId].orEmpty()
            if (identities.isEmpty() && user.username !in extraSubjects) {
                continue
            }
            principals.add(
                RelayPrincipal(
                    subject = user.username,
                    displayName = user.username,
                    // Roles are read live from the user record every time this
                    // runs, so a demotion reaches the relay on the next push and
                    // the phone on the next request after that.
                    roles = user.roles.map { it.name }.sorted(),
                    identities = identities.map {
                        RelayExternalIdentity(
                            provider = it.provider,
                            subject = it.providerSubject,
                            label = it.label
                        )
                    }
                )
            )
        }
        principals.sortBy { it.subject }
        return principals
    }

    /**
     * A stable digest of the principal list.
     *
     * The publisher pushes principals only when this changes (plus a periodic
     * re-push to self-heal), so a steady state costs one snapshot per interval
     * rather than the whole authorization table every minute.
     */
    open fun digest(principals: List<RelayPrincipal>): String {
        // A change-detection fingerprint over usernames and role names. No
        // secret is involved; see RelayDigest.
        val md = RelayDigest.newAccumulator()
        for (p in principals) {
            md.update(p.subject.toByteArray(Charsets.UTF_8))
            md.update(0)
            for (role in p.roles) {
                md.update(role.toByteArray(Charsets.UTF_8))
                md.update(1)
            }
            for (identity in p.identities) {
                md.update(identity.provider.toByteArray(Charsets.UTF_8))
                md.update(2)
                md.update(identity.subject.toByteArray(Charsets.UTF_8))
                md.update(3)
            }
            md.update(if (p.disabled) 1 else 0)
            md.update(4)
        }
        return RelayDigest.hex(md.digest())
    }

    /**
     * Validates a proposed identity link and returns the resolved user id.
     *
     * @throws IllegalArgumentException with a message safe to show an ADMIN
     */
    open fun validateLink(request: CreateRelayIdentityRequest): Long {
        val username = request.username.trim()
        if (username.isEmpty() || username.length > 254) {
            throw IllegalArgumentException("username is required")
        }
        val provider = request.provider.trim().lowercase()
        if (provider !in RelayIdentity.Provider.ALL) {
            throw IllegalArgumentException("provider must be one of ${RelayIdentity.Provider.ALL.sorted()}")
        }

        val subject = request.providerSubject.trim()
        if (subject.isEmpty() || subject.length > 255) {
            throw IllegalArgumentException("providerSubject is required")
        }
        // The relay applies the same character class; rejecting here gives the
        // admin the error at link time instead of at the next push.
        if (!subject.all { it.isLetterOrDigit() || it in ".-_|@" }) {
            throw IllegalArgumentException("providerSubject may only contain letters, digits and . - _ | @")
        }
        // An email or a login name is not a stable identifier: both can be
        // changed, and a released GitHub login can be claimed by somebody else.
        if (provider == RelayIdentity.Provider.GITHUB && !subject.all { it.isDigit() }) {
            throw IllegalArgumentException(
                "For GitHub, providerSubject must be the numeric account id (see https://api.github.com/users/<login>), not the login name"
            )
        }

        val user = userRepository.findByUsername(username).orElse(null)
            ?: throw IllegalArgumentException("No secman user named '${sanitizeForLog(username)}'")
        val userId = user.id ?: throw IllegalArgumentException("The user record has no id")

        // Warn-and-refuse rather than silently create something that will not
        // work: an ADMIN bound only to GitHub could never sign in, and the
        // failure would surface as an opaque 403 in the app.
        val isPrivileged = user.roles.any { it.name == "ADMIN" }
        if (isPrivileged && provider !in RelayIdentity.Provider.STRONG) {
            throw IllegalArgumentException(
                "'${sanitizeForLog(username)}' holds ADMIN, which may only sign in with " +
                    "${RelayIdentity.Provider.STRONG.sorted()}. Link an Apple or Google account instead."
            )
        }

        relayIdentityRepository.findByProviderAndProviderSubject(provider, subject)?.let { existing ->
            if (existing.userId != userId) {
                throw IllegalArgumentException("That $provider account is already linked to another secman user")
            }
        }
        relayIdentityRepository.findByUserIdAndProvider(userId, provider)?.let {
            throw IllegalArgumentException("'${sanitizeForLog(username)}' already has a $provider account linked")
        }

        return userId
    }

    /** Normalises a provider name for storage. */
    open fun normalizeProvider(provider: String): String = provider.trim().lowercase()
}
