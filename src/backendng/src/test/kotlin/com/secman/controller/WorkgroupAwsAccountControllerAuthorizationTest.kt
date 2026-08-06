package com.secman.controller

import com.secman.domain.User
import com.secman.domain.Workgroup
import com.secman.repository.UserRepository
import com.secman.repository.WorkgroupRepository
import com.secman.service.WorkgroupAwsAccountService
import io.micronaut.http.HttpStatus
import io.micronaut.security.authentication.Authentication
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.Optional

/**
 * Regression coverage for the `list` endpoint's authorization: it must be
 * member-or-ADMIN scoped like the sibling `add`/`remove` handlers, per the
 * documented "ADMIN/member-scoped" contract in CLAUDE.md.
 */
class WorkgroupAwsAccountControllerAuthorizationTest {

    private lateinit var service: WorkgroupAwsAccountService
    private lateinit var userRepository: UserRepository
    private lateinit var workgroupRepository: WorkgroupRepository
    private lateinit var controller: WorkgroupAwsAccountController

    private val member = User(id = 1L, username = "member", email = "member@example.com", passwordHash = "x")
    private val outsider = User(id = 2L, username = "outsider", email = "outsider@example.com", passwordHash = "x")
    private val admin = User(id = 3L, username = "admin", email = "admin@example.com", passwordHash = "x")

    @BeforeEach
    fun setUp() {
        service = mockk()
        userRepository = mockk()
        workgroupRepository = mockk()
        controller = WorkgroupAwsAccountController(service, userRepository, workgroupRepository)

        every { userRepository.findByUsername(member.username) } returns Optional.of(member)
        every { userRepository.findByUsername(outsider.username) } returns Optional.of(outsider)
        every { userRepository.findByUsername(admin.username) } returns Optional.of(admin)
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

    private fun auth(user: User, roles: Set<String> = setOf("USER")): Authentication = mockk {
        every { name } returns user.username
        every { this@mockk.roles } returns roles
    }
}
