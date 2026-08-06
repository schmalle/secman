package com.secman.service

import com.secman.domain.User
import com.secman.repository.UserRepository
import io.mockk.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.Optional

class UserResolutionServiceTest {

    private lateinit var userRepository: UserRepository
    private lateinit var userMappingService: UserMappingService
    private lateinit var service: UserResolutionService

    @BeforeEach
    fun setUp() {
        userRepository = mockk()
        userMappingService = mockk(relaxed = true)
        service = UserResolutionService(userRepository, userMappingService)
    }

    @Test
    fun `lazy-create uses default roles when roles parameter is null`() {
        every { userRepository.findByEmailIgnoreCase("new@example.com") } returns Optional.empty()
        every { userRepository.existsByUsername(any()) } returns false
        val saved = slot<User>()
        every { userRepository.save(capture(saved)) } answers { saved.captured.also { it.id = 42L } }

        service.resolveByIdOrEmail(userId = null, email = "new@example.com", context = "target")

        assertEquals(setOf(User.Role.USER, User.Role.VULN, User.Role.REQ), saved.captured.roles)
    }

    @Test
    fun `lazy-create honors explicit roles parameter`() {
        every { userRepository.findByEmailIgnoreCase("invite@example.com") } returns Optional.empty()
        every { userRepository.existsByUsername(any()) } returns false
        val saved = slot<User>()
        every { userRepository.save(capture(saved)) } answers { saved.captured.also { it.id = 43L } }

        service.resolveByIdOrEmail(
            userId = null,
            email = "invite@example.com",
            context = "target",
            roles = setOf(User.Role.USER, User.Role.VULN),
        )

        assertEquals(setOf(User.Role.USER, User.Role.VULN), saved.captured.roles)
    }
}
