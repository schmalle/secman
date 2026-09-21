package com.secman.service

import com.secman.domain.*
import com.secman.repository.*
import io.mockk.*
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.Optional

/** Checks that synchronization preserves independently granted access. */
class WorkgroupOwnerGrantTest {
    private val grants = mockk<WorkgroupAwsAccountRepository>(relaxed = true)
    private val groups = mockk<WorkgroupRepository>(relaxed = true)
    private val users = mockk<UserRepository>(relaxed = true)
    private val mappings = mockk<UserMappingRepository>(relaxed = true)
    private val entityManager = mockk<EntityManager>(relaxed = true)
    private val service = WorkgroupAwsAccountService(grants, groups, users, mappings, entityManager)
    private val admin = User(id = 1, username = "admin", email = "admin@example.test", passwordHash = "x", roles = mutableSetOf(User.Role.ADMIN))
    private val group = Workgroup(id = 2, name = "Group", ownerEmail = "owner@example.test")

    /** Keep repository fixtures explicit so denied paths cannot succeed through relaxed mocks. */
    @BeforeEach fun setup() {
        every { users.findById(1) } returns Optional.of(admin)
        every { entityManager.find(Workgroup::class.java, 2L, LockModeType.PESSIMISTIC_WRITE) } returns group
        every { grants.save(any()) } answers { firstArg() }
        every { grants.update(any()) } answers { firstArg() }
    }
    @Test fun `obsolete owner grants cannot remove manual account grants`() {
        val manual = WorkgroupAwsAccount(id = 3, workgroup = group, awsAccountId = "123456789012", createdBy = admin, manualGrant = true, ownerSyncGrant = true)
        every { grants.findByWorkgroupId(2) } returns listOf(manual)
        service.reconcileOwner(2, 1, false, emptySet())
        assertTrue(manual.manualGrant)
        assertFalse(manual.ownerSyncGrant)
        verify(exactly = 0) { grants.delete(any()) }
    }
    @Test fun `obsolete sync only account grant is removed`() {
        val derived = WorkgroupAwsAccount(id = 3, workgroup = group, awsAccountId = "123456789012", createdBy = admin, manualGrant = false, ownerSyncGrant = true)
        every { grants.findByWorkgroupId(2) } returns listOf(derived)
        service.reconcileOwner(2, 1, false, emptySet())
        verify { grants.delete(derived) }
    }
    @Test fun `dry run performs no writes`() {
        every { mappings.findDistinctAwsAccountIdByEmail("owner@example.test") } returns listOf("123456789012")
        val result = service.reconcileOwner(2, 1, true, null)
        assertEquals(listOf("123456789012"), result["additions"])
        verify(exactly = 0) { grants.save(any()); grants.update(any()); grants.delete(any()) }
    }
    @Test fun `mapping change after preview rejects entire apply`() {
        every { mappings.findDistinctAwsAccountIdByEmail("owner@example.test") } returns listOf("123456789012")
        assertThrows(IllegalArgumentException::class.java) { service.reconcileOwner(2, 1, false, emptySet()) }
        verify(exactly = 0) { grants.save(any()); grants.update(any()); grants.delete(any()) }
    }
    @Test fun `secchampion cannot change canonical owner derived grants`() {
        admin.roles = mutableSetOf(User.Role.SECCHAMPION)
        assertThrows(IllegalArgumentException::class.java) { service.reconcileOwner(2, 1, false, emptySet()) }
    }
}
