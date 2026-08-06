package com.secman.service

import com.secman.domain.User
import com.secman.domain.Workgroup
import com.secman.repository.UserRepository
import com.secman.repository.WorkgroupAdDomainRepository
import com.secman.repository.WorkgroupRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.Optional

class WorkgroupAdDomainServiceTest {

    private val repository: WorkgroupAdDomainRepository = mockk()
    private val workgroupRepository: WorkgroupRepository = mockk()
    private val userRepository: UserRepository = mockk()
    private val cacheInvalidator: McpAccessibleAssetsCacheInvalidator = mockk(relaxed = true)
    private val service = WorkgroupAdDomainService(
        repository,
        workgroupRepository,
        userRepository,
        cacheInvalidator
    )

    private val workgroup = Workgroup(id = 42L, name = "Cloud Office")
    private val actor = User(id = 7L, username = "admin", email = "admin@example.com", passwordHash = "x")

    @Test
    fun `add normalizes domain before saving`() {
        val savedSlot = slot<com.secman.domain.WorkgroupAdDomain>()
        every { workgroupRepository.findById(42L) } returns Optional.of(workgroup)
        every { userRepository.findById(7L) } returns Optional.of(actor)
        every { repository.existsByWorkgroupIdAndAdDomain(42L, "corp.example.com") } returns false
        every { repository.save(capture(savedSlot)) } answers { savedSlot.captured.copy(id = 100L) }

        val saved = service.add(42L, "  CORP.Example.COM  ", 7L)

        assertEquals("corp.example.com", saved.adDomain)
        assertEquals(workgroup, saved.workgroup)
        assertEquals(actor, saved.createdBy)
        verify { cacheInvalidator.invalidate() }
    }

    @Test
    fun `add rejects duplicate normalized domain`() {
        every { repository.existsByWorkgroupIdAndAdDomain(42L, "corp.example.com") } returns true

        assertThrows(DuplicateAdDomainException::class.java) {
            service.add(42L, "CORP.EXAMPLE.COM", 7L)
        }
    }

    @Test
    fun `add rejects invalid domain`() {
        assertThrows(IllegalArgumentException::class.java) {
            service.add(42L, "corp example com", 7L)
        }
    }
}
