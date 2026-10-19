package com.secman.controller

import com.secman.dto.CommentScope
import com.secman.dto.CreateRequirementCommentRequest
import com.secman.service.RequirementCommentService
import io.micronaut.http.HttpResponse
import io.micronaut.http.annotation.*
import io.micronaut.security.annotation.Secured
import io.micronaut.security.authentication.Authentication
import jakarta.validation.Valid

@Controller("/api/requirement-comments/{scope}/{targetId}")
@Secured("ADMIN", "REQ", "REQADMIN", "SECCHAMPION", "RELEASE_MANAGER")
open class RequirementCommentController(private val service: RequirementCommentService) {
    @Get
    open fun list(
        @PathVariable scope: CommentScope,
        @PathVariable targetId: Long,
        @QueryValue(defaultValue = "0") page: Int,
        @QueryValue(defaultValue = "20") size: Int,
        authentication: Authentication
    ) = HttpResponse.ok(service.list(scope, targetId, page, size, authentication))

    @Post
    open fun create(
        @PathVariable scope: CommentScope,
        @PathVariable targetId: Long,
        @Valid @Body request: CreateRequirementCommentRequest,
        authentication: Authentication
    ) = HttpResponse.created(service.create(scope, targetId, request, authentication))

    @Delete("/{commentId}")
    open fun delete(
        @PathVariable scope: CommentScope,
        @PathVariable targetId: Long,
        @PathVariable commentId: Long,
        authentication: Authentication
    ): HttpResponse<*> {
        service.delete(scope, targetId, commentId, authentication)
        return HttpResponse.noContent<Any>()
    }
}
