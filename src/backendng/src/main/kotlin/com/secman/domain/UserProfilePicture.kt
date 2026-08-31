package com.secman.domain

import com.fasterxml.jackson.annotation.JsonIgnore
import io.micronaut.serde.annotation.Serdeable
import jakarta.persistence.*
import java.time.Instant

/**
 * A user's profile picture (avatar).
 *
 * One row per user at most, enforced by a unique constraint on user_id. Held in a side table
 * rather than as a column on [User] because `users` is loaded on every authenticated request and
 * `@Basic(fetch = LAZY)` on a `@Lob` is inert without Hibernate bytecode enhancement (not enabled
 * in this build) - a blob column on `users` would therefore be fetched eagerly, every time.
 *
 * For the same reason, callers that only need metadata must use
 * `UserProfilePictureRepository.existsByUserId` / `findUpdatedAtByUserId` rather than
 * `findByUserId`, which always drags [content] along.
 *
 * [content] and [contentType] are always produced by `ProfilePictureService.normalize` - the
 * uploaded bytes and the client-declared content type are validated and then discarded, so
 * nothing an attacker controls is ever stored or served back.
 *
 * Uses a plain [userId] rather than a `@OneToOne User` relation: reading a picture must not load
 * a User, and a lazy proxy inside a `data class` equals/hashCode is a known hazard. The foreign
 * key is still enforced at the database level (see V251).
 */
@Entity
@Table(
    name = "user_profile_picture",
    uniqueConstraints = [
        UniqueConstraint(name = "uk_user_profile_picture_user", columnNames = ["user_id"])
    ]
)
@Serdeable
data class UserProfilePicture(
    @Id
    // IDENTITY is mandatory (CLAUDE.md Hard Principle 3): a bare @GeneratedValue resolves to a
    // native <table>_seq that fights the AUTO_INCREMENT column and yields intermittent
    // "Duplicate entry '<n>' for key 'PRIMARY'".
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "user_id", nullable = false, unique = true)
    var userId: Long,

    /** Always a service-chosen output type (image/png or image/jpeg) - never the client's. */
    @Column(name = "content_type", nullable = false, length = 64)
    var contentType: String,

    @Column(name = "file_size_bytes", nullable = false)
    var fileSizeBytes: Long,

    @Column(nullable = false)
    var width: Int,

    @Column(nullable = false)
    var height: Int,

    /** SHA-256 of [content], served as the ETag. */
    @Column(nullable = false, length = 64)
    var sha256: String,

    /** Sanitized original filename, kept for audit only. Never echoed into a response header. */
    @Column(name = "original_filename", length = 255)
    var originalFilename: String? = null,

    @JsonIgnore
    @Lob
    @Basic(fetch = FetchType.LAZY)
    @Column(name = "content", nullable = false, columnDefinition = "LONGBLOB")
    var content: ByteArray,

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()
) {
    // equals/hashCode on id only - never on `content`, which has ByteArray identity semantics.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is UserProfilePicture) return false
        return id != null && id == other.id
    }

    override fun hashCode(): Int = id?.hashCode() ?: 0

    override fun toString(): String =
        "UserProfilePicture(id=$id, userId=$userId, contentType=$contentType, " +
            "fileSizeBytes=$fileSizeBytes, width=$width, height=$height)"
}
