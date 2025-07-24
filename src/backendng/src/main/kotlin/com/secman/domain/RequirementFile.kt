package com.secman.domain

import io.micronaut.serde.annotation.Serdeable
import jakarta.persistence.*
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.time.LocalDateTime

@Entity
@Table(name = "requirement_files")
@Serdeable
data class RequirementFile(
    @Id
    @GeneratedValue
    var id: Long? = null,

    @Column(name = "filename", nullable = false)
    @NotBlank
    @Size(max = 255)
    var filename: String,

    @Column(name = "original_filename", nullable = false)
    @NotBlank
    @Size(max = 255)
    var originalFilename: String,

    @Column(name = "file_path", nullable = false, length = 500)
    @NotBlank
    @Size(max = 500)
    var filePath: String,

    @Column(name = "file_size", nullable = false)
    @NotNull
    var fileSize: Long,

    @Column(name = "content_type", nullable = false, length = 100)
    @NotBlank
    @Size(max = 100)
    var contentType: String,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "uploaded_by", nullable = false)
    @NotNull
    var uploadedBy: User,

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: LocalDateTime? = null,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime? = null
) {
    @PrePersist
    fun onCreate() {
        val now = LocalDateTime.now()
        createdAt = now
        updatedAt = now
    }

    @PreUpdate
    fun onUpdate() {
        updatedAt = LocalDateTime.now()
    }

    /**
     * Create a safe representation for API responses (without sensitive path info)
     */
    fun toSafeResponse(): RequirementFileResponse {
        return RequirementFileResponse(
            id = id!!,
            filename = filename,
            originalFilename = originalFilename,
            fileSize = fileSize,
            contentType = contentType,
            uploadedBy = uploadedBy.username,
            createdAt = createdAt!!,
            updatedAt = updatedAt!!
        )
    }

    override fun toString(): String {
        return "RequirementFile(id=$id, filename='$filename', originalFilename='$originalFilename', " +
               "fileSize=$fileSize, contentType='$contentType', uploadedBy=${uploadedBy.username})"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RequirementFile) return false
        return id != null && id == other.id
    }

    override fun hashCode(): Int {
        return id?.hashCode() ?: 0
    }
}

@Serdeable
data class RequirementFileResponse(
    val id: Long,
    val filename: String,
    val originalFilename: String,
    val fileSize: Long,
    val contentType: String,
    val uploadedBy: String,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime
)

@Entity
@Table(name = "risk_assessment_requirement_files")
@Serdeable
data class RiskAssessmentRequirementFile(
    @Id
    @GeneratedValue
    var id: Long? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "risk_assessment_id", nullable = false)
    @NotNull
    var riskAssessment: RiskAssessment,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "requirement_id", nullable = false)
    @NotNull
    var requirement: Requirement,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "file_id", nullable = false)
    @NotNull
    var file: RequirementFile,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "uploaded_by", nullable = false)
    @NotNull
    var uploadedBy: User,

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: LocalDateTime? = null
) {
    @PrePersist
    fun onCreate() {
        createdAt = LocalDateTime.now()
    }

    override fun toString(): String {
        return "RiskAssessmentRequirementFile(id=$id, riskAssessmentId=${riskAssessment.id}, " +
               "requirementId=${requirement.id}, fileId=${file.id})"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RiskAssessmentRequirementFile) return false
        return id != null && id == other.id
    }

    override fun hashCode(): Int {
        return id?.hashCode() ?: 0
    }
}