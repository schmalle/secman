package com.secman.domain

import jakarta.persistence.*
import java.time.LocalDateTime

/** Durable outcome correlation; RUNNING is not proof that a worker is still alive. */
@Entity
@Table(name = "crowdstrike_repair_request")
data class CrowdStrikeRepairRequest(
    @Id @Column(name = "request_id", length = 36) var requestId: String,
    @Column(name = "asset_id", nullable = false) var assetId: Long,
    @Column(name = "dry_run", nullable = false) var dryRun: Boolean,
    @Column(nullable = false, length = 255) var actor: String,
    @Column(nullable = false, length = 64) var fingerprint: String,
    @Column(nullable = false, length = 20) var state: String = "RUNNING",
    @Column(name = "http_status", nullable = false) var httpStatus: Int = 202,
    @Column(name = "error_code", length = 64) var errorCode: String? = null,
    @Column(length = 512) var message: String? = null,
    @Column(length = 64) var aid: String? = null,
    @Lob @Column(name = "response_json", columnDefinition = "LONGTEXT") var responseJson: String? = null,
    @Column(name = "started_at", nullable = false) var startedAt: LocalDateTime,
    @Column(name = "completed_at") var completedAt: LocalDateTime? = null
)
