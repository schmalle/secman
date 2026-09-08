package com.secman.domain

import jakarta.persistence.*
import java.time.Instant

@Entity
@Table(name = "integration_run", uniqueConstraints = [
    UniqueConstraint(name = "uk_integration_run_retry", columnNames = ["scanner_id", "subject_id", "run_key"])
], indexes = [Index(name = "idx_integration_run_subject", columnList = "subject_id,completed_at")])
class IntegrationRun(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long? = null,
    @Column(name = "scanner_id", nullable = false) var scannerId: Long = 0,
    @Column(name = "subject_id", nullable = false) var subjectId: Long = 0,
    @Column(name = "run_key", nullable = false, length = 128, columnDefinition = "VARCHAR(128) COLLATE utf8mb4_bin") var runKey: String = "",
    @Column(name = "content_digest", nullable = false, length = 64) var contentDigest: String = "",
    @Column(nullable = false, length = 20) var status: String = "",
    @Column(name = "complete_coverage", nullable = false) var completeCoverage: Boolean = false,
    @Column(name = "started_at", nullable = false) var startedAt: Instant = Instant.EPOCH,
    @Column(name = "completed_at", nullable = false) var completedAt: Instant = Instant.EPOCH,
    @Column(nullable = false) var accepted: Int = 0,
    @Column(nullable = false) var resolved: Int = 0,
    @Column(name = "metadata_json", nullable = false, columnDefinition = "TEXT") var metadataJson: String = "{}",
    // Immutable text evidence per run, without attachment base64 (stored separately).
    @Column(name = "findings_json", nullable = false, columnDefinition = "LONGTEXT") var findingsJson: String = "[]"
)
