package com.secman.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.secman.domain.Norm
import com.secman.domain.Requirement
import com.secman.repository.NormRepository
import com.secman.repository.RequirementRepository
import com.secman.repository.TranslationConfigRepository
import io.micronaut.http.client.HttpClient
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jakarta.inject.Provider
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class NormMappingServiceTest {
    private val requirements = mockk<RequirementRepository>()
    private val service = NormMappingService(
        requirements,
        mockk<NormRepository>(),
        mockk<TranslationConfigRepository>(),
        mockk<HttpClient>(),
        mockk<ObjectMapper>(),
        mockk<Provider<NormMappingService>>()
    )

    @Test
    fun `requested requirements use one batch while preserving order and duplicates`() {
        val first = Requirement(id = 1L, shortreq = "First")
        val second = Requirement(id = 2L, shortreq = "Second")
        val mapped = Requirement(id = 3L, shortreq = "Mapped", norms = mutableSetOf(mockk<Norm>()))
        every { requirements.findAllById(any<Iterable<Long>>()) } returns listOf(first, second, mapped)

        val result = service.loadRequestedUnmappedRequirements(listOf(2L, 1L, 3L, 99L, 2L))

        assertThat(result).containsExactly(second, first, second)
        verify(exactly = 1) { requirements.findAllById(match { it.toList() == listOf(2L, 1L, 3L, 99L) }) }
        verify(exactly = 0) { requirements.findById(any()) }
    }
}
