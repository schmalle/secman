package com.secman.service

import com.secman.domain.Release
import com.secman.domain.RequirementComment
import com.secman.dto.*
import com.secman.repository.*
import io.micronaut.data.model.Pageable
import io.micronaut.data.model.Sort
import io.micronaut.http.HttpStatus
import io.micronaut.http.exceptions.HttpStatusException
import io.micronaut.security.authentication.Authentication
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import jakarta.inject.Singleton
import jakarta.transaction.Transactional
import org.slf4j.LoggerFactory

@Singleton
open class RequirementCommentService(
    private val comments: RequirementCommentRepository,
    private val requirements: RequirementRepository,
    private val releases: ReleaseRepository,
    private val snapshots: RequirementSnapshotRepository,
    private val users: UserRepository,
    private val entityManager: EntityManager
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val discussionRoles = setOf("ADMIN", "REQ", "REQADMIN", "SECCHAMPION", "RELEASE_MANAGER")
    private val requirementRoles = setOf("ADMIN", "REQ", "SECCHAMPION")

    private data class Target(val writable: Boolean, val revision: Int?)

    // Requirements have global role-based visibility; they are not asset/workgroup scoped.
    private fun target(scope: CommentScope, id: Long, auth: Authentication, writing: Boolean = false): Target {
        val roles = if (scope == CommentScope.REQUIREMENT) requirementRoles else discussionRoles
        if (auth.roles.none { it in roles }) {
            log.warn("Comment access denied: actor={} scope={} target={} outcome=denied",
                auth.name.replace('\r', '_').replace('\n', '_'), scope, id)
            throw HttpStatusException(HttpStatus.FORBIDDEN, "Requirement discussion access required")
        }
        if (id <= 0) throw HttpStatusException(HttpStatus.NOT_FOUND, "Comment target not found")
        return when (scope) {
            CommentScope.REQUIREMENT -> {
                val requirement = requirements.findById(id).orElseThrow {
                    HttpStatusException(HttpStatus.NOT_FOUND, "Comment target not found")
                }
                if (writing) entityManager.refresh(requirement, LockModeType.PESSIMISTIC_WRITE)
                Target(requirement.isCurrent, requirement.versionNumber)
            }
            CommentScope.RELEASE -> {
                val release = releases.findById(id).orElseThrow {
                    HttpStatusException(HttpStatus.NOT_FOUND, "Comment target not found")
                }
                if (writing) entityManager.refresh(release, LockModeType.PESSIMISTIC_WRITE)
                Target(release.status != Release.ReleaseStatus.ARCHIVED, null)
            }
            CommentScope.SNAPSHOT -> {
                val snapshot = snapshots.findById(id).orElseThrow {
                    HttpStatusException(HttpStatus.NOT_FOUND, "Comment target not found")
                }
                if (writing) entityManager.refresh(snapshot.release, LockModeType.PESSIMISTIC_WRITE)
                Target(snapshot.release.status != Release.ReleaseStatus.ARCHIVED, snapshot.revision)
            }
        }
    }

    @Transactional
    open fun list(scope: CommentScope, id: Long, page: Int, size: Int, auth: Authentication): RequirementCommentPage {
        val target = target(scope, id, auth)
        if (page < 0 || size !in 1..100 || page > 1000000) {
            throw HttpStatusException(HttpStatus.BAD_REQUEST, "Invalid comment pagination")
        }
        val pageable = Pageable.from(page, size, Sort.of(Sort.Order.desc("createdAt"), Sort.Order.desc("id")))
        val actorId = actorId(auth)
        val moderator = isModerator(auth)
        val result = when (scope) {
            CommentScope.REQUIREMENT -> if (moderator) comments.findByRequirementId(id, pageable)
                else comments.findByRequirementIdAndAuthorId(id, actorId, pageable)
            CommentScope.RELEASE -> if (moderator) comments.findByReleaseId(id, pageable)
                else comments.findByReleaseIdAndAuthorId(id, actorId, pageable)
            CommentScope.SNAPSHOT -> if (moderator) comments.findBySnapshotId(id, pageable)
                else comments.findBySnapshotIdAndAuthorId(id, actorId, pageable)
        }
        return RequirementCommentPage(result.content.map { response(it, moderator || it.authorId == actorId) }, page, result.totalPages,
            result.totalSize, target.writable, target.revision)
    }

    @Transactional
    open fun create(scope: CommentScope, id: Long, request: CreateRequirementCommentRequest, auth: Authentication): RequirementCommentResponse {
        // Serialize the revision/status check with concurrent edits and release transitions.
        val target = target(scope, id, auth, writing = true)
        if (!target.writable) throw HttpStatusException(HttpStatus.CONFLICT, "This discussion is read-only")
        if (target.revision != null && request.expectedRevision != target.revision) {
            throw HttpStatusException(HttpStatus.CONFLICT, "Requirement revision changed; reload before commenting")
        }
        val body = request.body.trim()
        if (body.isBlank() || request.body.length > 2000) {
            throw HttpStatusException(HttpStatus.BAD_REQUEST, "Comment must contain 1 to 2000 characters")
        }
        val author = users.findByUsername(auth.name).orElseThrow {
            HttpStatusException(HttpStatus.FORBIDDEN, "Comment author unavailable")
        }
        if (!author.enabled) throw HttpStatusException(HttpStatus.FORBIDDEN, "Comment author unavailable")
        val saved = comments.save(RequirementComment(
            requirementId = if (scope == CommentScope.REQUIREMENT) id else null,
            releaseId = if (scope == CommentScope.RELEASE) id else null,
            snapshotId = if (scope == CommentScope.SNAPSHOT) id else null,
            requirementRevision = target.revision,
            authorId = author.id, authorName = author.username, body = body
        ))
        log.info("Comment created: actorId={} scope={} target={} commentId={} outcome=created",
            author.id, scope, id, saved.id)
        return response(saved, true)
    }

    private fun isModerator(auth: Authentication) = auth.roles.any { it == "ADMIN" || it == "SECCHAMPION" }

    private fun actorId(auth: Authentication): Long = users.findByUsername(auth.name)
        .filter { it.enabled }.map { it.id!! }.orElseThrow {
            HttpStatusException(HttpStatus.FORBIDDEN, "Comment author unavailable")
        }

    @Transactional
    open fun delete(scope: CommentScope, id: Long, commentId: Long, auth: Authentication) {
        target(scope, id, auth)
        val actorId = actorId(auth)
        val comment = comments.findById(commentId).orElseThrow {
            HttpStatusException(HttpStatus.NOT_FOUND, "Comment not found")
        }
        val targetMatches = when (scope) {
            CommentScope.REQUIREMENT -> comment.requirementId == id
            CommentScope.RELEASE -> comment.releaseId == id
            CommentScope.SNAPSHOT -> comment.snapshotId == id
        }
        if (!targetMatches || (!isModerator(auth) && comment.authorId != actorId)) {
            log.warn("Comment deletion denied: actorId={} scope={} target={} commentId={} outcome=denied",
                actorId, scope, id, commentId)
            throw HttpStatusException(HttpStatus.NOT_FOUND, "Comment not found")
        }
        // Removing discussion does not change the archived requirement or release content.
        comments.delete(comment)
        log.info("Comment deleted: actorId={} scope={} target={} commentId={} outcome=deleted",
            actorId, scope, id, commentId)
    }

    private fun response(comment: RequirementComment, canDelete: Boolean) = RequirementCommentResponse(
        comment.id!!, comment.body, comment.authorName, comment.createdAt, comment.requirementRevision, canDelete
    )
}
