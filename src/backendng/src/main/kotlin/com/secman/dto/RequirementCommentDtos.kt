package com.secman.dto

import com.fasterxml.jackson.annotation.JsonInclude
import io.micronaut.serde.annotation.Serdeable
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.Instant

@Serdeable
enum class CommentScope { REQUIREMENT, RELEASE, SNAPSHOT }

@Serdeable
data class CreateRequirementCommentRequest(
    @field:NotBlank @field:Size(max = 2000) val body: String,
    val expectedRevision: Int? = null
)

@Serdeable
data class RequirementCommentResponse(
    val id: Long,
    val body: String,
    val authorName: String,
    val createdAt: Instant,
    val requirementRevision: Int?,
    val canDelete: Boolean
)

@Serdeable
@JsonInclude(JsonInclude.Include.ALWAYS)
data class RequirementCommentPage(
    val data: List<RequirementCommentResponse>,
    val page: Int,
    val totalPages: Int,
    val totalItems: Long,
    val canComment: Boolean,
    val targetRevision: Int?
)
