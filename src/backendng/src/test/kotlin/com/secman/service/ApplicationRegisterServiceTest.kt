package com.secman.service

import com.secman.domain.ApplicationRegister
import com.secman.domain.Asset
import com.secman.dto.ApplicationRegisterRequest
import com.secman.repository.ApplicationRegisterRepository
import com.secman.repository.AssetRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.Optional

class ApplicationRegisterServiceTest {

    private val repository: ApplicationRegisterRepository = mockk()
    private val assetRepository: AssetRepository = mockk()
    private val service = ApplicationRegisterService(repository, assetRepository)

    @Test
    fun `create trims required strings before saving`() {
        val savedSlot = slot<ApplicationRegister>()
        every { repository.existsByCarIdIgnoreCase("CAR-123") } returns false
        every { repository.save(capture(savedSlot)) } answers { savedSlot.captured.apply { id = 10L } }

        val response = service.create(
            validRequest(
                carId = "  CAR-123  ",
                name = "  Application Name  ",
                businessOwner = "  Business Owner  ",
                applicationManager = "  Manager  "
            ),
            "creator"
        )

        assertEquals("CAR-123", response.carId)
        assertEquals("Application Name", response.name)
        assertEquals("Business Owner", response.businessOwner)
        assertEquals("Manager", response.applicationManager)
        assertEquals("creator", savedSlot.captured.createdBy)
        assertEquals("creator", savedSlot.captured.updatedBy)
    }

    @Test
    fun `create rejects blank required fields`() {
        val exception = assertThrows(IllegalArgumentException::class.java) {
            service.create(validRequest(carId = " ", businessOwner = ""), "creator")
        }

        assertEquals("carId is required; businessOwner is required", exception.message)
    }

    @Test
    fun `replaceAssets rejects unknown asset ids`() {
        val application = ApplicationRegister(
            carId = "CAR-123",
            name = "Application",
            businessOwner = "Owner",
            applicationManager = "Manager"
        ).apply { id = 10L }
        val asset = Asset(name = "Asset One", type = "SERVER", owner = "Owner").apply { id = 1L }

        every { repository.findByIdWithAssets(10L) } returns Optional.of(application)
        every { assetRepository.findByIdIn(listOf(1L, 2L)) } returns listOf(asset)

        val exception = assertThrows(IllegalArgumentException::class.java) {
            service.replaceAssets(10L, listOf(1L, 2L), "updater")
        }

        assertEquals("Unknown asset ids: 2", exception.message)
    }

    @Test
    fun `replaceAssets returns linked asset summaries`() {
        val application = ApplicationRegister(
            carId = "CAR-123",
            name = "Application",
            businessOwner = "Owner",
            applicationManager = "Manager"
        ).apply { id = 10L }
        val asset = Asset(name = "Asset One", type = "SERVER", owner = "Owner").apply { id = 1L }
        val savedSlot = slot<ApplicationRegister>()

        every { repository.findByIdWithAssets(10L) } returns Optional.of(application)
        every { assetRepository.findByIdIn(listOf(1L)) } returns listOf(asset)
        every { repository.update(capture(savedSlot)) } answers { savedSlot.captured }

        val response = service.replaceAssets(10L, listOf(1L), "updater")

        assertEquals(listOf(1L), response.assets.map { it.id })
        assertEquals("updater", savedSlot.captured.updatedBy)
        verify { repository.update(any<ApplicationRegister>()) }
    }

    private fun validRequest(
        carId: String = "CAR-123",
        name: String = "Application",
        businessOwner: String = "Owner",
        applicationManager: String = "Manager"
    ): ApplicationRegisterRequest {
        return ApplicationRegisterRequest(
            carId = carId,
            name = name,
            businessOwner = businessOwner,
            applicationManager = applicationManager
        )
    }
}
