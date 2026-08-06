package com.secman.controller

import com.secman.domain.User
import com.secman.domain.Workgroup
import com.secman.domain.WorkgroupAdDomain
import com.secman.repository.UserMappingRepository
import com.secman.repository.UserRepository
import com.secman.repository.WorkgroupRepository
import com.secman.service.WorkgroupAdDomainService
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
 * Authorization coverage for [WorkgroupAdDomainController].
 *
 * Two contracts are pinned here:
 *
 * 1. `list` is member-or-ADMIN scoped like the sibling `add`/`remove` handlers, per the
 *    documented "ADMIN/member-scoped" contract in CLAUDE.md.
 *
 * 2. `add` additionally requires that a non-ADMIN actor already reaches the domain through their
 *    own domain UserMapping. Membership alone is not enough: any user can create a workgroup and
 *    is auto-enrolled as a member, so a membership-only check let a plain USER bind an arbitrary
 *    AD domain and thereby read every asset in it.
 */
class WorkgroupAdDomainControllerAuthorizationTest {

    private lateinit var service: WorkgroupAdDomainService
    private lateinit var userRepository: UserRepository
    private lateinit var workgroupRepository: WorkgroupRepository
    private lateinit var userMappingRepository: UserMappingRepository
    private lateinit var controller: WorkgroupAdDomainController

    private val member = User(id = 1L, username = "member", email = "member@example.com", passwordHash = "x")
    private val outsider = User(id = 2L, username = "outsider", email = "outsider@example.com", passwordHash = "x")
    private val admin = User(id = 3L, username = "admin", email = "admin@example.com", passwordHash = "x")

    private val ownedDomain = "corp.example.com"
    private val foreignDomain = "secret.example.com"

    @BeforeEach
    fun setUp() {
        service = mockk()
        userRepository = mockk()
        workgroupRepository = mockk()
        userMappingRepository = mockk()
        controller = WorkgroupAdDomainController(service, userRepository, workgroupRepository, userMappingRepository)

        every { userRepository.findByUsername(member.username) } returns Optional.of(member)
        every { userRepository.findByUsername(outsider.username) } returns Optional.of(outsider)
        every { userRepository.findByUsername(admin.username) } returns Optional.of(admin)

        every { userMappingRepository.findDistinctDomainByEmail(member.email) } returns listOf(ownedDomain)
    }

    @Test
    fun `non-member non-admin cannot list workgroup ad domains`() {
        val workgroup = Workgroup(id = 20L, name = "wg-20", createdBy = member, users = mutableSetOf(member))
        every { workgroupRepository.findById(20L) } returns Optional.of(workgroup)

        val response = controller.list(20L, auth(outsider))

        assertEquals(HttpStatus.FORBIDDEN, response.status)
    }

    @Test
    fun `member can list their workgroup ad domains`() {
        val workgroup = Workgroup(id = 21L, name = "wg-21", createdBy = member, users = mutableSetOf(member))
        every { workgroupRepository.findById(21L) } returns Optional.of(workgroup)
        every { service.list(21L) } returns emptyList()

        val response = controller.list(21L, auth(member))

        assertEquals(HttpStatus.OK, response.status)
    }

    @Test
    fun `admin can list any workgroup ad domains`() {
        every { service.list(22L) } returns emptyList()

        val response = controller.list(22L, auth(admin, roles = setOf("ADMIN")))

        assertEquals(HttpStatus.OK, response.status)
    }

    // --- `add`: ownership gate (privilege-escalation regression) ---

    @Test
    fun `member cannot bind an ad domain they have no access to`() {
        val workgroup = workgroupOwnedByMember(40L)
        every { workgroupRepository.findById(40L) } returns Optional.of(workgroup)

        val response = controller.add(40L, AddAdDomainRequest(foreignDomain), auth(member))

        assertEquals(HttpStatus.FORBIDDEN, response.status)
        io.mockk.verify(exactly = 0) { service.add(any(), any(), any()) }
    }

    @Test
    fun `member can bind an ad domain from their own user mapping`() {
        val workgroup = workgroupOwnedByMember(41L)
        every { workgroupRepository.findById(41L) } returns Optional.of(workgroup)
        every { service.add(41L, ownedDomain, member.id!!) } returns persisted(workgroup, ownedDomain)

        val response = controller.add(41L, AddAdDomainRequest(ownedDomain), auth(member))

        assertEquals(HttpStatus.CREATED, response.status)
    }

    @Test
    fun `ad domain ownership match is case-insensitive`() {
        val workgroup = workgroupOwnedByMember(42L)
        val upperCased = ownedDomain.uppercase()
        every { workgroupRepository.findById(42L) } returns Optional.of(workgroup)
        every { service.add(42L, upperCased, member.id!!) } returns persisted(workgroup, upperCased)

        val response = controller.add(42L, AddAdDomainRequest(upperCased), auth(member))

        assertEquals(HttpStatus.CREATED, response.status)
    }

    @Test
    fun `admin can bind any ad domain without a mapping`() {
        val workgroup = workgroupOwnedByMember(43L)
        every { service.add(43L, foreignDomain, admin.id!!) } returns persisted(workgroup, foreignDomain)

        val response = controller.add(43L, AddAdDomainRequest(foreignDomain), auth(admin, roles = setOf("ADMIN")))

        assertEquals(HttpStatus.CREATED, response.status)
    }

    @Test
    fun `non-member cannot bind even a domain they own`() {
        val workgroup = workgroupOwnedByMember(44L)
        every { workgroupRepository.findById(44L) } returns Optional.of(workgroup)
        every { userMappingRepository.findDistinctDomainByEmail(outsider.email) } returns listOf(ownedDomain)

        val response = controller.add(44L, AddAdDomainRequest(ownedDomain), auth(outsider))

        assertEquals(HttpStatus.FORBIDDEN, response.status)
        io.mockk.verify(exactly = 0) { service.add(any(), any(), any()) }
    }

    private fun workgroupOwnedByMember(id: Long) =
        Workgroup(id = id, name = "wg-$id", createdBy = member, users = mutableSetOf(member))

    private fun persisted(workgroup: Workgroup, domain: String) = WorkgroupAdDomain(
        id = 100L,
        workgroup = workgroup,
        adDomain = domain,
        createdBy = member,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH
    )

    private fun auth(user: User, roles: Set<String> = setOf("USER")): Authentication = mockk {
        every { name } returns user.username
        every { this@mockk.roles } returns roles
    }
}
