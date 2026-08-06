package com.secman.domain

import io.micronaut.serde.annotation.Serdeable
import jakarta.persistence.*
import java.time.Instant

/**
 * Exception from the GitHub repo 30-day non-decrease alerting. A repository
 * with an active exception is skipped by the alert run and reported in the
 * result's `reposExcepted` list. Expired exceptions are ignored (the repo is
 * alerted again) but kept for audit.
 */
@Entity
@Table(
    name = "github_repo_alert_exception",
    indexes = [
        Index(name = "idx_ghexc_repo", columnList = "github_repository_id")
    ]
)
@Serdeable
data class GithubRepoAlertException(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "github_repository_id", nullable = false)
    var githubRepositoryId: Long = 0,

    @Column(name = "reason", nullable = false, length = 1024)
    var reason: String = "",

    /** Null = never expires. */
    @Column(name = "expiration_date")
    var expirationDate: Instant? = null,

    @Column(name = "created_by", nullable = false, length = 255)
    var createdBy: String = "",

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now()
) {
    fun isActive(now: Instant = Instant.now()): Boolean {
        return expirationDate == null || expirationDate!!.isAfter(now)
    }
}
