package com.secman.service

import com.secman.domain.AppSettings
import com.secman.domain.Workgroup
import com.secman.domain.WorkgroupAccessChangedEvent
import com.secman.repository.AppSettingsRepository
import com.secman.repository.WorkgroupRepository
import io.micronaut.context.event.ApplicationEventPublisher
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import java.util.Optional

class CatchAllWorkgroupSafetyServiceTest {
    private val settingsRepository = mockk<AppSettingsRepository>()
    private val workgroupRepository = mockk<WorkgroupRepository>()
    private val publisher = mockk<ApplicationEventPublisher<WorkgroupAccessChangedEvent>>(relaxed = true)
    private val service = CatchAllWorkgroupSafetyService(settingsRepository, workgroupRepository, publisher)

    @Test
    fun `import eligibility respects default and configured threshold inclusively`() {
        every { settingsRepository.findFirstSettings() } returns Optional.empty()
        assertFalse(service.exceedsMembershipLimit(99))
        assertEquals(true, service.exceedsMembershipLimit(100))
        every { settingsRepository.findFirstSettings() } returns Optional.of(AppSettings(catchAllWorkgroupUserThreshold = 2))
        assertFalse(service.exceedsMembershipLimit(1))
        assertEquals(true, service.exceedsMembershipLimit(2))
    }

    @Test
    fun `qualifying enabled workgroup is disabled and access cache is invalidated`() {
        val workgroup = Workgroup(id = 42L, name = "catch-all")
        every { settingsRepository.findFirstSettings() } returns Optional.of(AppSettings(catchAllWorkgroupUserThreshold = 100))
        every { workgroupRepository.countUsersByWorkgroupId(42L) } returns 100L
        every { workgroupRepository.findById(42L) } returns Optional.of(workgroup)
        every { workgroupRepository.update(workgroup) } returns workgroup

        val disabled = service.enforceAffected(setOf(42L), "admin")

        assertEquals(setOf(42L), disabled)
        assertFalse(workgroup.enabled)
        verify(exactly = 1) { publisher.publishEvent(match { it.workgroupIds == setOf(42L) }) }
    }

    @Test
    fun `workgroup below threshold is not changed`() {
        every { settingsRepository.findFirstSettings() } returns Optional.empty()
        every { workgroupRepository.countUsersByWorkgroupId(42L) } returns 99L

        assertEquals(emptySet<Long>(), service.enforceAffected(setOf(42L)))

        verify(exactly = 0) { workgroupRepository.update(any()) }
        verify(exactly = 0) { publisher.publishEvent(any()) }
    }

    @Test
    fun `disabled workgroup is never auto-enabled when membership falls`() {
        every { settingsRepository.findFirstSettings() } returns Optional.of(AppSettings(catchAllWorkgroupUserThreshold = 100))
        every { workgroupRepository.countUsersByWorkgroupId(42L) } returns 20L

        service.enforceAffected(setOf(42L))

        verify(exactly = 0) { workgroupRepository.update(any()) }
    }
}
