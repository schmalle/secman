package com.secman.domain

import jakarta.persistence.*

@Entity
@Table(name = "integration_attachment", indexes = [Index(name = "idx_integration_attachment_finding_run", columnList = "finding_id,run_id")])
class IntegrationAttachment(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long? = null,
    @Column(name = "finding_id", nullable = false) var findingId: Long = 0,
    @Column(name = "run_id", nullable = false) var runId: Long = 0,
    @Column(name = "file_name", nullable = false, length = 200) var fileName: String = "",
    @Column(name = "content_type", nullable = false, length = 50) var contentType: String = "",
    @Lob @Column(nullable = false, columnDefinition = "MEDIUMBLOB") var content: ByteArray = byteArrayOf()
)
