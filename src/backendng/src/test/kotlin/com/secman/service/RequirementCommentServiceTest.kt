package com.secman.service

import com.secman.domain.*
import com.secman.dto.*
import com.secman.repository.*
import io.micronaut.data.model.Page
import io.micronaut.data.model.Pageable
import io.micronaut.http.HttpStatus
import io.micronaut.http.exceptions.HttpStatusException
import io.micronaut.security.authentication.Authentication
import io.mockk.*
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.Optional
import java.util.UUID

class RequirementCommentServiceTest {
    private val comments = mockk<RequirementCommentRepository>()
    private val requirements = mockk<RequirementRepository>()
    private val releases = mockk<ReleaseRepository>()
    private val snapshots = mockk<RequirementSnapshotRepository>()
    private val users = mockk<UserRepository>()
    private val entityManager = mockk<EntityManager>(relaxed = true)
    private val service = RequirementCommentService(comments, requirements, releases, snapshots, users, entityManager)
    private val auth = Authentication.build("reviewer", listOf("REQ"))
    private val requirement = Requirement(id = 1, internalId = "REQ-1", shortreq = "Test")
    private val release = Release(id = 2, version = "1.0", name = "Test")
    private val snapshot = RequirementSnapshot(id = 3, release = release, originalRequirementId = 1, shortreq = "Frozen")
    private val author = User(id = 4, username = "reviewer", email = "reviewer@example.test",
        passwordHash = UUID.randomUUID().toString(), roles = mutableSetOf(User.Role.REQ))

    @BeforeEach
    fun setup() {
        every { requirements.findById(1) } returns Optional.of(requirement)
        every { releases.findById(2) } returns Optional.of(release)
        every { snapshots.findById(3) } returns Optional.of(snapshot)
        every { users.findByUsername(any()) } returns Optional.of(author)
        every { comments.save(any()) } answers { firstArg<RequirementComment>().also { it.id = 5 } }
        every { comments.findByRequirementId(1, any()) } returns Page.of(emptyList(), Pageable.from(0, 20), 0)
        every { comments.findByReleaseId(2, any()) } returns Page.of(emptyList(), Pageable.from(0, 20), 0)
        every { comments.findBySnapshotId(3, any()) } returns Page.of(emptyList(), Pageable.from(0, 20), 0)
        every { comments.findByRequirementIdAndAuthorId(1, 4, any()) } returns Page.of(emptyList(), Pageable.from(0, 20), 0)
        every { comments.findByReleaseIdAndAuthorId(2, 4, any()) } returns Page.of(emptyList(), Pageable.from(0, 20), 0)
        every { comments.findBySnapshotIdAndAuthorId(3, 4, any()) } returns Page.of(emptyList(), Pageable.from(0, 20), 0)
        every { comments.delete(any<RequirementComment>()) } just Runs
    }

    @Test
    fun `REQ-only user can create and read all scopes without changing targets`() {
        listOf(CommentScope.REQUIREMENT to 1L, CommentScope.RELEASE to 2L, CommentScope.SNAPSHOT to 3L).forEach { (scope, id) ->
            val result = service.create(scope, id, CreateRequirementCommentRequest("  <script>alert(1)</script>  ", 1), auth)
            assertThat(result.authorName).isEqualTo("reviewer")
            assertThat(result.body).isEqualTo("<script>alert(1)</script>")
            assertThat(service.list(scope, id, 0, 20, auth).canComment).isTrue()
        }
        assertThat(requirement.versionNumber).isEqualTo(1)
        assertThat(release.status).isEqualTo(Release.ReleaseStatus.PREPARATION)
        assertThat(snapshot.shortreq).isEqualTo("Frozen")
        verify(exactly = 1) { comments.save(match { it.requirementId == 1L && it.releaseId == null && it.snapshotId == null }) }
        verify(exactly = 1) { comments.save(match { it.releaseId == 2L && it.requirementId == null && it.snapshotId == null }) }
        verify(exactly = 1) { comments.save(match { it.snapshotId == 3L && it.requirementId == null && it.releaseId == null }) }
    }

    @Test
    fun `all non-archived release states permit discussion`() {
        Release.ReleaseStatus.entries.filter { it != Release.ReleaseStatus.ARCHIVED }.forEach { status ->
            release.status = status
            service.create(CommentScope.RELEASE, 2, CreateRequirementCommentRequest("Discuss"), auth)
            service.create(CommentScope.SNAPSHOT, 3, CreateRequirementCommentRequest("Discuss", 1), auth)
        }
    }

    @Test
    fun `archived release and its snapshots remain readable but reject new comments`() {
        release.status = Release.ReleaseStatus.ARCHIVED
        listOf(CommentScope.RELEASE to 2L, CommentScope.SNAPSHOT to 3L).forEach { (scope, id) ->
            assertThat(service.list(scope, id, 0, 20, auth).canComment).isFalse()
            assertStatus(HttpStatus.CONFLICT) { service.create(scope, id, CreateRequirementCommentRequest("No", 1), auth) }
        }
        verify(exactly = 0) { comments.save(any()) }
    }

    @Test
    fun `historical requirement is read-only`() {
        requirement.isCurrent = false
        assertThat(service.list(CommentScope.REQUIREMENT, 1, 0, 20, auth).canComment).isFalse()
        assertStatus(HttpStatus.CONFLICT) { service.create(CommentScope.REQUIREMENT, 1, CreateRequirementCommentRequest("No", 1), auth) }
    }

    @Test
    fun `current requirement retains comment revision and rejects stale or omitted revision`() {
        requirement.versionNumber = 2
        listOf(1, null).forEach { revision ->
            assertStatus(HttpStatus.CONFLICT) { service.create(CommentScope.REQUIREMENT, 1, CreateRequirementCommentRequest("Old", revision), auth) }
        }
        val result = service.create(CommentScope.REQUIREMENT, 1, CreateRequirementCommentRequest("New", 2), auth)
        assertThat(result.requirementRevision).isEqualTo(2)
    }

    @Test
    fun `write checks the refreshed state after acquiring the target lock`() {
        every { entityManager.refresh(requirement, LockModeType.PESSIMISTIC_WRITE) } answers {
            requirement.versionNumber = 2
        }
        assertStatus(HttpStatus.CONFLICT) {
            service.create(CommentScope.REQUIREMENT, 1, CreateRequirementCommentRequest("Stale", 1), auth)
        }
        every { entityManager.refresh(release, LockModeType.PESSIMISTIC_WRITE) } answers {
            release.status = Release.ReleaseStatus.ARCHIVED
        }
        listOf(CommentScope.RELEASE to 2L, CommentScope.SNAPSHOT to 3L).forEach { (scope, id) ->
            assertStatus(HttpStatus.CONFLICT) {
                service.create(scope, id, CreateRequirementCommentRequest("Archived", 1), auth)
            }
        }
        verify(exactly = 0) { comments.save(any()) }
    }

    @Test
    fun `invalid text and disabled author never persist`() {
        listOf("", "  \n\t", "a".repeat(2001)).forEach { text ->
            assertStatus(HttpStatus.BAD_REQUEST) { service.create(CommentScope.REQUIREMENT, 1, CreateRequirementCommentRequest(text, 1), auth) }
        }
        author.enabled = false
        assertStatus(HttpStatus.FORBIDDEN) { service.create(CommentScope.REQUIREMENT, 1, CreateRequirementCommentRequest("No", 1), auth) }
        verify(exactly = 0) { comments.save(any()) }
    }

    @Test
    fun `role denials happen before target queries`() {
        listOf("USER", "VULN", "RISK").forEach { role ->
            val denied = Authentication.build("viewer", listOf(role))
            CommentScope.entries.forEach { scope ->
                assertStatus(HttpStatus.FORBIDDEN) { service.list(scope, 99, 0, 20, denied) }
                assertStatus(HttpStatus.FORBIDDEN) { service.create(scope, 99, CreateRequirementCommentRequest("No", 1), denied) }
            }
        }
        verify { requirements wasNot Called; releases wasNot Called; snapshots wasNot Called; comments wasNot Called }
    }

    @Test
    fun `existing roles and multiple-role users retain access`() {
        listOf(listOf("ADMIN"), listOf("SECCHAMPION"), listOf("USER", "REQ")).forEach { roles ->
            assertThat(service.list(CommentScope.REQUIREMENT, 1, 0, 20, Authentication.build("actor", roles)).canComment).isTrue()
        }
        listOf("REQADMIN", "RELEASE_MANAGER").forEach { role ->
            val actor = Authentication.build("actor", listOf(role))
            assertThat(service.list(CommentScope.RELEASE, 2, 0, 20, actor).canComment).isTrue()
            assertStatus(HttpStatus.FORBIDDEN) { service.list(CommentScope.REQUIREMENT, 1, 0, 20, actor) }
        }
    }

    @Test
    fun `missing and invalid targets fail closed`() {
        every { requirements.findById(99) } returns Optional.empty()
        every { releases.findById(99) } returns Optional.empty()
        every { snapshots.findById(99) } returns Optional.empty()
        CommentScope.entries.forEach { scope ->
            listOf(-1L, 0L, 99L).forEach { id ->
                assertStatus(HttpStatus.NOT_FOUND) { service.list(scope, id, 0, 20, auth) }
            }
        }
    }

    @Test
    fun `pagination is bounded and ordered at the database`() {
        listOf(-1 to 20, 0 to 0, 0 to 101, 1000001 to 20).forEach { (page, size) ->
            assertStatus(HttpStatus.BAD_REQUEST) { service.list(CommentScope.REQUIREMENT, 1, page, size, auth) }
        }
        service.list(CommentScope.REQUIREMENT, 1, 2, 100, auth)
        verify { comments.findByRequirementIdAndAuthorId(1, 4, match { it.number == 2 && it.size == 100 && it.sort.orderBy.map { order -> order.property } == listOf("createdAt", "id") }) }
    }

    @Test
    fun `ordinary users query only their own comments and counts across all scopes`() {
        val own = RequirementComment(id = 8, requirementId = 1, authorId = 4, authorName = "reviewer", body = "Private")
        every { comments.findByRequirementIdAndAuthorId(1, 4, any()) } returns Page.of(listOf(own), Pageable.from(0, 20), 1)
        val result = service.list(CommentScope.REQUIREMENT, 1, 0, 20, auth)
        assertThat(result.totalItems).isEqualTo(1)
        assertThat(result.data.single().canDelete).isTrue()
        service.list(CommentScope.RELEASE, 2, 0, 20, auth)
        service.list(CommentScope.SNAPSHOT, 3, 0, 20, auth)
        verify(exactly = 0) { comments.findByRequirementId(any(), any()); comments.findByReleaseId(any(), any()); comments.findBySnapshotId(any(), any()) }
    }

    @Test
    fun `only moderators can list everyone's comments`() {
        listOf("ADMIN", "SECCHAMPION").forEach { role ->
            val moderator = Authentication.build("reviewer", listOf(role))
            service.list(CommentScope.REQUIREMENT, 1, 0, 20, moderator)
            service.list(CommentScope.RELEASE, 2, 0, 20, moderator)
            service.list(CommentScope.SNAPSHOT, 3, 0, 20, moderator)
        }
        verify(exactly = 2) { comments.findByRequirementId(1, any()); comments.findByReleaseId(2, any()); comments.findBySnapshotId(3, any()) }
        listOf("REQADMIN", "RELEASE_MANAGER").forEach { role ->
            service.list(CommentScope.RELEASE, 2, 0, 20, Authentication.build("reviewer", listOf(role)))
        }
        verify(exactly = 2) { comments.findByReleaseIdAndAuthorId(2, 4, any()) }
    }

    @Test
    fun `authors and moderators can delete across scopes even after archive`() {
        release.status = Release.ReleaseStatus.ARCHIVED
        listOf(CommentScope.REQUIREMENT to 1L, CommentScope.RELEASE to 2L, CommentScope.SNAPSHOT to 3L).forEach { (scope, id) ->
            val comment = RequirementComment(id = 8, requirementId = if (scope == CommentScope.REQUIREMENT) id else null,
                releaseId = if (scope == CommentScope.RELEASE) id else null,
                snapshotId = if (scope == CommentScope.SNAPSHOT) id else null,
                authorId = 4, authorName = "reviewer", body = "Delete")
            every { comments.findById(8) } returns Optional.of(comment)
            service.delete(scope, id, 8, auth)
            comment.authorId = null
            listOf("ADMIN", "SECCHAMPION").forEach { role ->
                service.delete(scope, id, 8, Authentication.build("reviewer", listOf(role)))
            }
        }
        verify(exactly = 9) { comments.delete(any<RequirementComment>()) }
    }

    @Test
    fun `other authors unknown comments and mismatched targets cannot be deleted`() {
        val comment = RequirementComment(id = 8, requirementId = 1, authorId = 99, authorName = "Someone else", body = "Secret")
        every { comments.findById(8) } returns Optional.of(comment)
        every { comments.findById(99) } returns Optional.empty()
        assertStatus(HttpStatus.NOT_FOUND) { service.delete(CommentScope.REQUIREMENT, 1, 8, auth) }
        comment.authorId = null
        assertStatus(HttpStatus.NOT_FOUND) { service.delete(CommentScope.REQUIREMENT, 1, 8, auth) }
        assertStatus(HttpStatus.NOT_FOUND) { service.delete(CommentScope.RELEASE, 2, 8, Authentication.build("reviewer", listOf("ADMIN"))) }
        assertStatus(HttpStatus.NOT_FOUND) { service.delete(CommentScope.REQUIREMENT, 1, 99, auth) }
        assertStatus(HttpStatus.FORBIDDEN) { service.delete(CommentScope.REQUIREMENT, 1, 8, Authentication.build("reviewer", listOf("USER"))) }
        verify(exactly = 0) { comments.delete(any<RequirementComment>()) }
    }

    private fun assertStatus(expected: HttpStatus, action: () -> Unit) {
        assertThat(assertThrows(HttpStatusException::class.java, action).status).isEqualTo(expected)
    }
}
