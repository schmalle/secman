package com.secman.controller

import com.secman.domain.User
import com.secman.domain.Workgroup
import com.secman.domain.WorkgroupAwsAccount
import com.secman.repository.UserMappingRepository
import com.secman.repository.UserRepository
import com.secman.repository.WorkgroupRepository
import com.secman.service.AwsAccountSharingService
import com.secman.service.WorkgroupAwsAccountService
import io.micronaut.http.HttpStatus
import io.micronaut.security.authentication.Authentication
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.Optional

/**
 * Authorization coverage for [WorkgroupAwsAccountController].
 *
 * Two contracts are pinned here:
 *
 * 1. `list` is member-or-ADMIN scoped like the sibling `add`/`remove` handlers, per the
 *    documented "ADMIN/member-scoped" contract in CLAUDE.md.
 *
 * 2. `add` additionally requires that a non-ADMIN actor already has access to the account being
 *    bound. Membership alone is not enough: any user can create a workgroup and is auto-enrolled
 *    as a member, so a membership-only check let a plain USER bind an arbitrary (enumerable)
 *    12-digit account and thereby read every asset and vulnerability in it.
 */
class WorkgroupAwsAccountControllerAuthorizationTest {

    private lateinit var service: WorkgroupAwsAccountService
    private lateinit var userRepository: UserRepository
    private lateinit var workgroupRepository: WorkgroupRepository
    private lateinit var userMappingRepository: UserMappingRepository
    private lateinit var awsAccountSharingService: AwsAccountSharingService
    private lateinit var controller: WorkgroupAwsAccountController

    private val member = User(id = 1L, username = "member", email = "member@example.com", passwordHash = "x")
    private val outsider = User(id = 2L, username = "outsider", email = "outsider@example.com", passwordHash = "x")
    private val admin = User(id = 3L, username = "admin", email = "admin@example.com", passwordHash = "x")

    private val ownedAccount = "111111111111"
    private val sharedAccount = "222222222222"
    private val foreignAccount = "999999999999"

    @BeforeEach
    fun setUp() {
        service = mockk()
        userRepository = mockk()
        workgroupRepository = mockk()
        userMappingRepository = mockk()
        awsAccountSharingService = mockk()
        controller = WorkgroupAwsAccountController(
            service, userRepository, workgroupRepository, userMappingRepository, awsAccountSharingService
        )

        every { userRepository.findByUsername(member.username) } returns Optional.of(member)
        every { userRepository.findByUsername(outsider.username) } returns Optional.of(outsider)
        every { userRepository.findByUsername(admin.username) } returns Optional.of(admin)

        // The member owns one account outright and reaches a second via a sharing rule.
        every { userMappingRepository.findDistinctAwsAccountIdByEmail(member.email) } returns listOf(ownedAccount)
        every { awsAccountSharingService.getSharedAwsAccountIdsByEmail(member.email) } returns listOf(sharedAccount)
    }

    @Test
    fun `non-member non-admin cannot list workgroup aws accounts`() {
        val workgroup = Workgroup(id = 10L, name = "wg-10", createdBy = member, users = mutableSetOf(member))
        every { workgroupRepository.findById(10L) } returns Optional.of(workgroup)

        val response = controller.list(10L, auth(outsider))

        assertEquals(HttpStatus.FORBIDDEN, response.status)
    }

    @Test
    fun `member can list their workgroup aws accounts`() {
        val workgroup = Workgroup(id = 11L, name = "wg-11", createdBy = member, users = mutableSetOf(member))
        every { workgroupRepository.findById(11L) } returns Optional.of(workgroup)
        every { service.list(11L) } returns emptyList()

        val response = controller.list(11L, auth(member))

        assertEquals(HttpStatus.OK, response.status)
    }

    @Test
    fun `admin can list any workgroup aws accounts`() {
        every { service.list(12L) } returns emptyList()

        val response = controller.list(12L, auth(admin, roles = setOf("ADMIN")))

        assertEquals(HttpStatus.OK, response.status)
    }

    // --- `add`: ownership gate (privilege-escalation regression) ---

    @Test
    fun `member cannot bind an aws account they have no access to`() {
        val workgroup = workgroupOwnedByMember(30L)
        every { workgroupRepository.findById(30L) } returns Optional.of(workgroup)

        val response = controller.add(30L, AddAwsAccountRequest(foreignAccount), auth(member))

        assertEquals(HttpStatus.FORBIDDEN, response.status)
        // The service must never be reached — the account must not be bound at all.
        io.mockk.verify(exactly = 0) { service.add(any(), any(), any()) }
    }

    @Test
    fun `member can bind an aws account from their own user mapping`() {
        val workgroup = workgroupOwnedByMember(31L)
        every { workgroupRepository.findById(31L) } returns Optional.of(workgroup)
        every { service.add(31L, ownedAccount, member.id!!) } returns persisted(workgroup, ownedAccount)

        val response = controller.add(31L, AddAwsAccountRequest(ownedAccount), auth(member))

        assertEquals(HttpStatus.CREATED, response.status)
    }

    @Test
    fun `member can bind an aws account shared with them`() {
        val workgroup = workgroupOwnedByMember(32L)
        every { workgroupRepository.findById(32L) } returns Optional.of(workgroup)
        every { service.add(32L, sharedAccount, member.id!!) } returns persisted(workgroup, sharedAccount)

        val response = controller.add(32L, AddAwsAccountRequest(sharedAccount), auth(member))

        assertEquals(HttpStatus.CREATED, response.status)
    }

    @Test
    fun `admin can bind any aws account without a mapping`() {
        val workgroup = workgroupOwnedByMember(33L)
        every { service.add(33L, foreignAccount, admin.id!!) } returns persisted(workgroup, foreignAccount)

        val response = controller.add(33L, AddAwsAccountRequest(foreignAccount), auth(admin, roles = setOf("ADMIN")))

        assertEquals(HttpStatus.CREATED, response.status)
    }

    @Test
    fun `non-member cannot bind even an account they own`() {
        val workgroup = workgroupOwnedByMember(34L)
        every { workgroupRepository.findById(34L) } returns Optional.of(workgroup)
        every { userMappingRepository.findDistinctAwsAccountIdByEmail(outsider.email) } returns listOf(ownedAccount)

        val response = controller.add(34L, AddAwsAccountRequest(ownedAccount), auth(outsider))

        assertEquals(HttpStatus.FORBIDDEN, response.status)
        io.mockk.verify(exactly = 0) { service.add(any(), any(), any()) }
    }

    private fun workgroupOwnedByMember(id: Long) =
        Workgroup(id = id, name = "wg-$id", createdBy = member, users = mutableSetOf(member))

    private fun persisted(workgroup: Workgroup, accountId: String) = WorkgroupAwsAccount(
        id = 100L,
        workgroup = workgroup,
        awsAccountId = accountId,
        createdBy = member,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH
    )

    private fun auth(user: User, roles: Set<String> = setOf("USER")): Authentication = mockk {
        every { name } returns user.username
        every { this@mockk.roles } returns roles
    }
}
