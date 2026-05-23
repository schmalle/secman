package com.secman.controller

import com.secman.domain.User
import com.secman.domain.Workgroup
import com.secman.repository.AssetRepository
import com.secman.repository.UserRepository
import com.secman.repository.WorkgroupAwsAccountRepository
import com.secman.repository.WorkgroupRepository
import com.secman.service.AssetFilterService
import com.secman.service.UserResolutionService
import com.secman.service.WorkgroupService
import io.micronaut.http.HttpStatus
import io.micronaut.security.authentication.Authentication
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.Optional

class WorkgroupAuthorizationTest {

    private lateinit var workgroupService: WorkgroupService
    private lateinit var assetRepository: AssetRepository
    private lateinit var workgroupAwsAccountRepository: WorkgroupAwsAccountRepository
    private lateinit var userResolutionService: UserResolutionService
    private lateinit var workgroupRepository: WorkgroupRepository
    private lateinit var userRepository: UserRepository
    private lateinit var assetFilterService: AssetFilterService
    private lateinit var controller: WorkgroupController

    private val creator = User(id = 10L, username = "creator", email = "creator@example.com", passwordHash = "x")
    private val member = User(id = 11L, username = "member", email = "member@example.com", passwordHash = "x")
    private val target = User(id = 12L, username = "target", email = "target@example.com", passwordHash = "x")

    @BeforeEach
    fun setUp() {
        workgroupService = mockk()
        assetRepository = mockk()
        workgroupAwsAccountRepository = mockk()
        userResolutionService = mockk()
        workgroupRepository = mockk()
        userRepository = mockk()
        assetFilterService = mockk()
        every { userRepository.findByUsername(creator.username) } returns Optional.of(creator)
        every { userRepository.findByUsername(member.username) } returns Optional.of(member)
        every { userRepository.findByUsername(target.username) } returns Optional.of(target)
        controller = WorkgroupController(
            workgroupService,
            assetRepository,
            workgroupAwsAccountRepository,
            userResolutionService,
            workgroupRepository,
            userRepository,
            assetFilterService
        )
    }

    @Test
    fun `regular user cannot add users to workgroup`() {
        val workgroup = workgroup(id = 101L, createdBy = member, users = mutableSetOf(member))
        every { workgroupRepository.findById(101L) } returns Optional.of(workgroup)

        val response = controller.assignUsers(
            101L,
            AssignUsersRequest(userIds = listOf(target.id!!)),
            auth(member)
        )

        assertEquals(HttpStatus.FORBIDDEN, response.status)
        verify(exactly = 0) { workgroupService.assignUsersToWorkgroup(any(), any()) }
    }

    @Test
    fun `creator can delete their workgroup`() {
        val workgroup = workgroup(id = 102L, createdBy = creator, users = mutableSetOf(creator))
        every { workgroupService.getWorkgroupById(102L) } returns workgroup
        every { workgroupService.deleteWorkgroupWithPromotion(102L) } just Runs

        val response = controller.deleteWorkgroup(102L, auth(creator))

        assertEquals(HttpStatus.NO_CONTENT, response.status)
        verify { workgroupService.deleteWorkgroupWithPromotion(102L) }
    }

    @Test
    fun `member who is not creator cannot delete workgroup`() {
        val workgroup = workgroup(id = 103L, createdBy = creator, users = mutableSetOf(member))
        every { workgroupService.getWorkgroupById(103L) } returns workgroup

        val response = controller.deleteWorkgroup(103L, auth(member))

        assertEquals(HttpStatus.FORBIDDEN, response.status)
        verify(exactly = 0) { workgroupService.deleteWorkgroupWithPromotion(any()) }
    }

    @Test
    fun `regular member can add accessible assets to accessible workgroup`() {
        val workgroup = workgroup(id = 104L, createdBy = member, users = mutableSetOf(member))
        every { workgroupRepository.findById(104L) } returns Optional.of(workgroup)
        every { assetFilterService.getAccessibleAssetIds(any()) } returns setOf(201L, 202L)
        every { workgroupService.assignAssetsToWorkgroup(104L, listOf(201L, 202L)) } just Runs

        val response = controller.assignAssets(
            104L,
            AssignAssetsRequest(assetIds = listOf(201L, 202L)),
            auth(member)
        )

        assertEquals(HttpStatus.OK, response.status)
        verify { workgroupService.assignAssetsToWorkgroup(104L, listOf(201L, 202L)) }
    }

    @Test
    fun `regular member cannot add inaccessible assets to workgroup`() {
        val workgroup = workgroup(id = 105L, createdBy = member, users = mutableSetOf(member))
        every { workgroupRepository.findById(105L) } returns Optional.of(workgroup)
        every { assetFilterService.getAccessibleAssetIds(any()) } returns setOf(201L)

        val response = controller.assignAssets(
            105L,
            AssignAssetsRequest(assetIds = listOf(201L, 999L)),
            auth(member)
        )

        assertEquals(HttpStatus.FORBIDDEN, response.status)
        verify(exactly = 0) { workgroupService.assignAssetsToWorkgroup(any(), any()) }
    }

    private fun workgroup(id: Long, createdBy: User, users: MutableSet<User>): Workgroup =
        Workgroup(
            id = id,
            name = "wg-$id",
            createdBy = createdBy,
            users = users
        )

    private fun auth(user: User, roles: Set<String> = setOf("USER")): Authentication = mockk {
        every { name } returns user.username
        every { this@mockk.roles } returns roles
        every { attributes } returns mapOf("userId" to user.id.toString(), "email" to user.email)
    }
}
