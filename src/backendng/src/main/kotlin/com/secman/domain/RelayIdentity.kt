package com.secman.domain

import io.micronaut.serde.annotation.Serdeable
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.PrePersist
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import jakarta.validation.constraints.NotBlank
import java.time.Instant

/**
 * Binds an external identity-provider account to a secman user, for the mobile
 * relay only.
 *
 * Why a separate table rather than a column on `users`: this mapping exists
 * solely so the relay can answer "which secman user is this Apple account?".
 * It is not a secman login path — nobody authenticates to secman with it — and
 * a user may have one identity per provider.
 *
 * Nothing secret is stored. [providerSubject] is the provider's public, stable
 * account identifier; the app proves possession of it to the *relay*, and the
 * relay only ever consults the copy secman pushed.
 */
@Entity
@Table(
    name = "relay_identity",
    uniqueConstraints = [
        // One provider account maps to at most one secman user. Without this,
        // two users could claim the same Apple account and the relay's
        // identity index would resolve to whichever was written last.
        UniqueConstraint(name = "uk_relay_identity_provider_subject", columnNames = ["provider", "provider_subject"]),
        // And a user has at most one account per provider.
        UniqueConstraint(name = "uk_relay_identity_user_provider", columnNames = ["user_id", "provider"])
    ],
    indexes = [Index(name = "idx_relay_identity_user", columnList = "user_id")]
)
@Serdeable
data class RelayIdentity(
    @Id
    // IDENTITY, not the AUTO default: on MariaDB, AUTO maps to a native
    // <table>_seq that fights the AUTO_INCREMENT column and yields intermittent
    // "Duplicate entry 'n' for key 'PRIMARY'". See docs/ARCHITECTURE.md.
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "user_id", nullable = false)
    var userId: Long,

    /** `apple`, `google` or `github`. Validated against [Provider] on the way in. */
    @Column(nullable = false, length = 16)
    @NotBlank
    var provider: String,

    /**
     * The provider's stable account identifier: Apple's / Google's `sub`, or
     * GitHub's numeric account id.
     *
     * Never an email address or a login name. Both are mutable, and a released
     * GitHub login can be claimed by somebody else — using one here would
     * silently transfer a user's mobile access to a stranger.
     */
    @Column(name = "provider_subject", nullable = false, length = 255)
    @NotBlank
    var providerSubject: String,

    /** Human hint shown in admin listings, e.g. the login name at the time of linking. */
    @Column(name = "label", length = 128)
    var label: String? = null,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),

    /** Username of the admin who created the mapping. Audit only. */
    @Column(name = "created_by", length = 255)
    var createdBy: String? = null
) {
    @PrePersist
    fun onCreate() {
        if (createdAt == Instant.EPOCH) createdAt = Instant.now()
    }

    /** The providers the relay understands. */
    object Provider {
        const val APPLE = "apple"
        const val GOOGLE = "google"
        const val GITHUB = "github"

        val ALL = setOf(APPLE, GOOGLE, GITHUB)

        /**
         * Providers strong enough for a privileged (ADMIN) account.
         *
         * This mirrors the relay's own `RELAY_STRONG_PROVIDERS` default. secman
         * validates against it so an admin is told at link time that a GitHub
         * account will not work for them, instead of finding out at the 403.
         * The relay re-checks independently — this copy is UX, not the boundary.
         */
        val STRONG = setOf(APPLE, GOOGLE)
    }
}
