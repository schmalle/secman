package com.secman.service

import com.secman.domain.*
import com.secman.repository.*
import io.micronaut.security.authentication.Authentication
import io.mockk.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.Optional

/** Verifies that old export files do not outlive their authorization. */
class ExportAuthorizationTest {
    private val jobs = mockk<ExportJobRepository>(relaxed = true)
    private val users = mockk<UserRepository>(relaxed = true)
    private val filter = mockk<AssetFilterService>(relaxed = true)
    private val service = ExportJobService(jobs, users, mockk(), filter, mockk(), mockk(), mockk(),
        System.getProperty("java.io.tmpdir"), 1, 5, 24)
    private val user = User(id = 1, username = "user", email = "user@example.test", passwordHash = "x")
    private val job = ExportJob(id = "job", username = user.username)
    private val auth = Authentication.build(user.username, listOf("USER"), mapOf("userId" to 1, "email" to user.email))
    /** Keep repository fixtures explicit so denied paths cannot succeed through relaxed mocks. */
    @BeforeEach fun setup() {
        every { jobs.findById("job") } returns Optional.of(job)
        every { users.findById(1) } returns Optional.of(user)
        every { jobs.update(any()) } answers { firstArg() }
        every { filter.getAccessibleAssetIds(any()) } returns setOf(2L, 3L)
    }
    @Test fun `revoked asset invalidates stored export`() {
        service.bindScope("job", auth, "2,3")
        assertTrue(service.scopeStillValid(job))
        every { filter.getAccessibleAssetIds(any()) } returns setOf(2L)
        assertFalse(service.scopeStillValid(job))
        assertThrows(SecurityException::class.java) { service.requireCurrentScope("job") }
    }
    @Test fun `scope change during job preparation rejects start`() {
        assertThrows(IllegalStateException::class.java) { service.bindScope("job", auth, "2,3,4") }
        verify(exactly = 0) { jobs.update(any()) }
    }
    @Test fun `disabled and deleted actors cannot download`() {
        service.bindScope("job", auth, "2,3")
        user.enabled = false
        assertFalse(service.scopeStillValid(job))
        every { users.findById(1) } returns Optional.empty()
        assertFalse(service.scopeStillValid(job))
    }
    @Test fun `historical exports without bound scope fail closed`() {
        assertFalse(service.scopeStillValid(job))
    }
}
