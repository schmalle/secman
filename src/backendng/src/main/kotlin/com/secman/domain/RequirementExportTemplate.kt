package com.secman.domain

import io.micronaut.serde.annotation.Serdeable
import jakarta.persistence.*
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant

@Entity
@Table(
    name = "requirement_export_template",
    indexes = [
        Index(name = "idx_req_export_template_status_created", columnList = "status, created_at"),
        Index(name = "idx_req_export_template_sha256", columnList = "sha256")
    ]
)
@Serdeable
data class RequirementExportTemplate(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(nullable = false, length = 200)
    var name: String,

    @Column(columnDefinition = "TEXT")
    var description: String? = null,

    @Column(name = "version_label", length = 100)
    var versionLabel: String? = null,

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    var status: RequirementExportTemplateStatus = RequirementExportTemplateStatus.ACTIVE,

    @Column(name = "original_filename", nullable = false, length = 255)
    var originalFilename: String,

    @Column(name = "content_type", nullable = false, length = 128)
    var contentType: String,

    @Column(name = "file_size_bytes", nullable = false)
    var fileSizeBytes: Long,

    @Column(nullable = false, length = 64)
    var sha256: String,

    @Lob
    @Basic(fetch = FetchType.LAZY)
    @Column(name = "content", nullable = false, columnDefinition = "LONGBLOB")
    var content: ByteArray,

    @Column(name = "validation_report_json", columnDefinition = "TEXT")
    var validationReportJson: String? = null,

    @Column(name = "uploaded_by", nullable = false, length = 255)
    var uploadedBy: String,

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now(),

    @Column(name = "activated_at")
    var activatedAt: Instant? = Instant.now(),

    @Column(name = "deactivated_at")
    var deactivatedAt: Instant? = null,

    @Column(name = "last_used_at")
    var lastUsedAt: Instant? = null
)

enum class RequirementExportTemplateStatus {
    ACTIVE,
    INACTIVE,
    RETIRED,
    REJECTED
}
