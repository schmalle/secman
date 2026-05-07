package com.secman.controller

import com.secman.domain.MappingStatus
import com.secman.domain.User
import com.secman.domain.UserMapping
import com.secman.dto.AwsAccountSharingResponse
import com.secman.dto.CreateAwsAccountSharingRequest
import com.secman.repository.UserMappingRepository
import com.secman.repository.UserRepository
import com.secman.service.AwsAccountSharingService
import io.micronaut.http.HttpStatus
import io.micronaut.security.authentication.Authentication
import io.mockk.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.Optional

class AwsAccountSharingControllerInviteValidatorTest {

    private lateinit var service: AwsAccountSharingService
    private lateinit var userRepository: UserRepository
    private lateinit var userMappingRepository: UserMappingRepository
    private lateinit var controller: AwsAccountSharingController

    private val callerId = 1L
    private val caller = User(id = callerId, username = "alice", email = "alice@example.com", passwordHash = "x")

    @BeforeEach
    fun setUp() {
        service = mockk()
        userRepository = mockk()
        userMappingRepository = mockk()
        controller = AwsAccountSharingController(service, userRepository, userMappingRepository)
        every { userRepository.findById(callerId) } returns Optional.of(caller)
    }

    private fun authStub(): Authentication = mockk {
        every { attributes } returns mapOf("userId" to callerId)
        every { roles } returns setOf("USER", "VULN")
        every { name } returns caller.username
    }

    @Test
    fun `400 when email is malformed`() {
        val request = CreateAwsAccountSharingRequest(
            sourceUserId = callerId,
            targetUserEmail = "not-an-email",
            inviteByEmail = true,
        )
        val response = controller.createSharingRule(request, authStub())
        assertEquals(HttpStatus.BAD_REQUEST, response.status)
    }

    @Test
    fun `400 when domain mismatch`() {
        val request = CreateAwsAccountSharingRequest(
            sourceUserId = callerId,
            targetUserEmail = "bob@otherco.com",
            inviteByEmail = true,
        )
        val response = controller.createSharingRule(request, authStub())
        assertEquals(HttpStatus.BAD_REQUEST, response.status)
    }

    @Test
    fun `400 when target email equals caller`() {
        val request = CreateAwsAccountSharingRequest(
            sourceUserId = callerId,
            targetUserEmail = "alice@example.com",
            inviteByEmail = true,
        )
        val response = controller.createSharingRule(request, authStub())
        assertEquals(HttpStatus.BAD_REQUEST, response.status)
    }

    @Test
    fun `409 when target email is already a User`() {
        every { userRepository.findByEmailIgnoreCase("bob@example.com") } returns
            Optional.of(User(id = 2L, username = "bob", email = "bob@example.com", passwordHash = "x"))
        val request = CreateAwsAccountSharingRequest(
            sourceUserId = callerId,
            targetUserEmail = "bob@example.com",
            inviteByEmail = true,
        )
        val response = controller.createSharingRule(request, authStub())
        assertEquals(HttpStatus.CONFLICT, response.status)
    }

    @Test
    fun `409 when target email matches a PENDING UserMapping`() {
        every { userRepository.findByEmailIgnoreCase("pending@example.com") } returns Optional.empty()
        every { userMappingRepository.findByEmailAndStatus("pending@example.com", MappingStatus.PENDING) } returns
            listOf(mockk<UserMapping>(relaxed = true))
        val request = CreateAwsAccountSharingRequest(
            sourceUserId = callerId,
            targetUserEmail = "pending@example.com",
            inviteByEmail = true,
        )
        val response = controller.createSharingRule(request, authStub())
        assertEquals(HttpStatus.CONFLICT, response.status)
    }

    @Test
    fun `delegates to service when validation passes`() {
        every { userRepository.findByEmailIgnoreCase("newbie@example.com") } returns Optional.empty()
        every { userMappingRepository.findByEmailAndStatus("newbie@example.com", MappingStatus.PENDING) } returns
            emptyList()
        every { service.createSharingRule(any(), callerId) } returns mockk<AwsAccountSharingResponse>()
        val request = CreateAwsAccountSharingRequest(
            sourceUserId = callerId,
            targetUserEmail = "newbie@example.com",
            inviteByEmail = true,
        )
        val response = controller.createSharingRule(request, authStub())
        assertEquals(HttpStatus.CREATED, response.status)
        verify { service.createSharingRule(match { it.inviteByEmail && it.targetUserEmail == "newbie@example.com" }, callerId) }
    }
}
