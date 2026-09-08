package com.secman.domain

import jakarta.persistence.*
import java.time.Instant

@Entity
@Table(name = "integration_finding", uniqueConstraints = [
    UniqueConstraint(name = "uk_integration_finding_identity", columnNames = ["subject_id", "external_id"]),
    UniqueConstraint(name = "uk_integration_finding_projection", columnNames = ["vulnerability_id"])
], indexes = [Index(name = "idx_integration_finding_state", columnList = "subject_id,state")])
class IntegrationFinding(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long? = null,
    @Column(name = "subject_id", nullable = false) var subjectId: Long = 0,
    @Column(name = "external_id", nullable = false, length = 200, columnDefinition = "VARCHAR(200) COLLATE utf8mb4_bin") var externalId: String = "",
    @Column(nullable = false, length = 20) var severity: String = "",
    @Column(nullable = false, length = 20) var state: String = "OPEN",
    @Column(nullable = false, length = 500) var title: String = "",
    @Column(columnDefinition = "TEXT") var description: String? = null,
    @Column(columnDefinition = "TEXT") var recommendation: String? = null,
    @Column(columnDefinition = "TEXT") var evidence: String? = null,
    @Column(name = "file_path", length = 1024) var filePath: String? = null,
    @Column(name = "line_range", length = 100) var lineRange: String? = null,
    @Column(length = 2048) var url: String? = null,
    @Column var confidence: Double? = null,
    @Column(length = 100) var engine: String? = null,
    @Column(length = 200) var model: String? = null,
    @Column(name = "commit_sha", length = 64) var commitSha: String? = null,
    @Column(name = "issue_url", length = 2048) var issueUrl: String? = null,
    @Column(name = "fix_pr_url", length = 2048) var fixPrUrl: String? = null,
    @Column(name = "first_seen_at", nullable = false) var firstSeenAt: Instant = Instant.EPOCH,
    @Column(name = "last_seen_at", nullable = false) var lastSeenAt: Instant = Instant.EPOCH,
    @Column(name = "resolved_at") var resolvedAt: Instant? = null,
    @Column(name = "vulnerability_id") var vulnerabilityId: Long? = null,
    @Column(name = "projection_key", nullable = false, length = 255) var projectionKey: String = "",
    @Column(name = "projection_product", length = 512) var projectionProduct: String? = null,
    @Column(name = "last_run_id", nullable = false) var lastRunId: Long = 0
)
