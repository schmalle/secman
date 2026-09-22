package com.secman.domain

import jakarta.persistence.*
import java.time.Instant

@Entity
@Table(name = "web_component", uniqueConstraints = [
    UniqueConstraint(name = "uk_web_component_identity", columnNames = ["subject_id", "component_key"])
], indexes = [
    Index(name = "idx_web_component_current", columnList = "subject_id,state,category"),
    Index(name = "idx_web_component_name", columnList = "name")
])
class WebComponent(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long? = null,
    @Column(name = "subject_id", nullable = false) var subjectId: Long = 0,
    @Column(name = "component_key", nullable = false, length = 128, columnDefinition = "VARCHAR(128) COLLATE utf8mb4_bin") var componentKey: String = "",
    @Column(nullable = false, length = 32) var category: String = "",
    @Column(nullable = false, length = 255) var name: String = "",
    @Column(length = 100) var version: String? = null,
    @Column(nullable = false) var confidence: Double = 0.0,
    @Column(name = "evidence_type", nullable = false, length = 32) var evidenceType: String = "",
    @Column(nullable = false, length = 512) var evidence: String = "",
    @Column(name = "source_url", length = 2048) var sourceUrl: String? = null,
    @Column(nullable = false, length = 20) var state: String = "OPEN",
    @Column(name = "first_seen_at", nullable = false) var firstSeenAt: Instant = Instant.EPOCH,
    @Column(name = "last_seen_at", nullable = false) var lastSeenAt: Instant = Instant.EPOCH,
    @Column(name = "resolved_at") var resolvedAt: Instant? = null,
    @Column(name = "last_run_id", nullable = false) var lastRunId: Long = 0
)
