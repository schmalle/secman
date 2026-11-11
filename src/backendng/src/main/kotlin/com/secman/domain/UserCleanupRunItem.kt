package com.secman.domain

import io.micronaut.serde.annotation.Serdeable
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant

@Serdeable
enum class UserCleanupOutcome { DELETED, WOULD_DELETE, WOULD_WARN, SKIPPED, WARNED, WARNING_FAILED, ERROR }

/**
 * What a run did to one user. Username and email are kept on purpose (decided): after the
 * user row is gone this is the only record of who was removed.
 */
@Entity
@Serdeable
@Table(
    name = "user_cleanup_run_item",
    indexes = [Index(name = "idx_user_cleanup_item_run_outcome", columnList = "run_id, outcome")]
)
data class UserCleanupRunItem(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "run_id", nullable = false)
    var runId: Long,

    @Column(name = "user_id", nullable = false)
    var userId: Long,

    @Column(name = "username", nullable = false)
    var username: String,

    @Column(name = "email", nullable = false)
    var email: String,

    @Column(name = "last_activity_at")
    var lastActivityAt: Instant? = null,

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false, length = 32)
    var outcome: UserCleanupOutcome,

    @Column(name = "reason", length = 64)
    var reason: String? = null,

    @Column(name = "details_json", columnDefinition = "TEXT")
    var detailsJson: String? = null
)
