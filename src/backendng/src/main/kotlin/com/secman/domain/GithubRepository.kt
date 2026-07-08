package com.secman.domain

import io.micronaut.serde.annotation.Serdeable
import jakarta.persistence.*
import java.time.Instant

/**
 * A GitHub repository accessible via the configured GitHub App.
 *
 * Rows are created/updated by the repo import (`import-github-repos` CLI /
 * `import_github_repos` MCP → `POST /api/github/import`), which upserts by
 * the stable numeric [githubRepoId] (rename-safe). `criticalCount` /
 * `highCount` hold the open Dependabot alert counts from the last import;
 * the per-import history lives in [GithubRepoFindingSnapshot].
 */
@Entity
@Table(
    name = "github_repository",
    uniqueConstraints = [
        UniqueConstraint(name = "uk_github_repo_id", columnNames = ["github_repo_id"]),
        UniqueConstraint(name = "uk_github_repo_full_name", columnNames = ["full_name"])
    ],
    indexes = [
        Index(name = "idx_github_repo_owner", columnList = "owner")
    ]
)
@Serdeable
data class GithubRepository(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    /** GitHub's stable numeric repository id. */
    @Column(name = "github_repo_id", nullable = false)
    var githubRepoId: Long = 0,

    @Column(name = "name", nullable = false, length = 255)
    var name: String = "",

    /** Owner login (org or user). */
    @Column(name = "owner", nullable = false, length = 255)
    var owner: String = "",

    /** `owner/name`. */
    @Column(name = "full_name", nullable = false, length = 512)
    var fullName: String = "",

    @Column(name = "html_url", length = 1024)
    var htmlUrl: String? = null,

    /** Notification address for the 30-day alert; editable, null = unmapped. */
    @Column(name = "owner_email", length = 255)
    var ownerEmail: String? = null,

    /** Open critical Dependabot alerts as of the last import. */
    @Column(name = "critical_count", nullable = false)
    var criticalCount: Int = 0,

    /** Open high Dependabot alerts as of the last import. */
    @Column(name = "high_count", nullable = false)
    var highCount: Int = 0,

    @Column(name = "last_import_at")
    var lastImportAt: Instant? = null,

    /** Last import at which the repo had at least one open high/critical alert. */
    @Column(name = "last_high_critical_finding_at")
    var lastHighCriticalFindingAt: Instant? = null,

    @Column(name = "archived", nullable = false)
    var archived: Boolean = false,

    @Column(name = "created_at")
    var createdAt: Instant? = null,

    @Column(name = "updated_at")
    var updatedAt: Instant? = null
) {
    @PrePersist
    fun onCreate() {
        val now = Instant.now()
        createdAt = createdAt ?: now
        updatedAt = now
    }

    @PreUpdate
    fun onUpdate() {
        updatedAt = Instant.now()
    }
}
