package com.secman.domain

import io.micronaut.serde.annotation.Serdeable
import jakarta.persistence.*
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant

@Entity
@Table(
    name = "requirement_export_template_usage",
    indexes = [
        Index(name = "idx_req_export_template_usage_template", columnList = "template_id"),
        Index(name = "idx_req_export_template_usage_created", columnList = "created_at")
    ]
)
@Serdeable
data class RequirementExportTemplateUsage(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "template_id")
    var template: RequirementExportTemplate? = null,

    @Column(name = "template_sha256", length = 64)
    var templateSha256: String? = null,

    @Column(name = "exported_by", nullable = false, length = 255)
    var exportedBy: String,

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "export_scope", nullable = false, length = 20)
    var exportScope: RequirementExportScope,

    @Column(name = "release_id")
    var releaseId: Long? = null,

    @Column(name = "usecase_id")
    var usecaseId: Long? = null,

    @Column(length = 32)
    var language: String? = null,

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "template_mode", nullable = false, length = 20)
    var templateMode: RequirementExportTemplateMode,

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now()
)

enum class RequirementExportScope {
    ALL,
    USE_CASE,
    RELEASE,
    TRANSLATED,
    MCP,
    CLI
}

enum class RequirementExportTemplateMode {
    LATEST,
    SAVED,
    ADHOC,
    NONE
}

enum class MissingPlaceholderBehavior {
    REJECT,
    APPEND
}
