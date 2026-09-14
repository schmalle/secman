package com.secman.service

import com.secman.domain.Requirement
import com.secman.domain.UseCase
import com.secman.repository.NormRepository
import com.secman.repository.RequirementRepository
import com.secman.repository.UseCaseRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.Optional

class McpRequirementManagementServiceTest {
    private val requirementService = mockk<RequirementService>(relaxed = true)
    private val requirementRepository = mockk<RequirementRepository>(relaxed = true)
    private val useCaseRepository = mockk<UseCaseRepository>(relaxed = true)
    private val normRepository = mockk<NormRepository>(relaxed = true)
    private val service = McpRequirementManagementService(
        requirementService,
        requirementRepository,
        useCaseRepository,
        normRepository
    )

    @Test
    fun `content update clears fields and increments revision`() {
        val requirement = Requirement(
            id = 4L,
            internalId = "REQ-004",
            shortreq = "Old",
            details = "Remove me",
            chapter = "Old chapter"
        )
        every { requirementRepository.findById(4L) } returns Optional.of(requirement)
        every { requirementRepository.update(any()) } answers { firstArg() }

        val saved = service.updateRequirement(
            4L,
            RequirementChanges(shortreq = "New", clearFields = setOf("details"), chapter = "New chapter"),
            9L
        )

        assertThat(saved.shortreq).isEqualTo("New")
        assertThat(saved.details).isNull()
        assertThat(saved.chapter).isEqualTo("New chapter")
        assertThat(saved.versionNumber).isEqualTo(2)
    }

    @Test
    fun `assignment replacement is exact and does not increment revision`() {
        val old = UseCase(id = 1L, name = "Old")
        val replacement = UseCase(id = 2L, name = "Cloud")
        val requirement = Requirement(
            id = 4L,
            internalId = "REQ-004",
            shortreq = "Requirement",
            usecases = mutableSetOf(old)
        )
        every { requirementRepository.findById(4L) } returns Optional.of(requirement)
        every { useCaseRepository.findById(2L) } returns Optional.of(replacement)
        every { requirementRepository.update(any()) } answers { firstArg() }

        val saved = service.replaceRequirementUseCases(4L, listOf(2L), 9L)

        assertThat(saved.usecases).containsExactly(replacement)
        assertThat(saved.versionNumber).isEqualTo(1)
    }

    @Test
    fun `missing relationship aborts requirement creation`() {
        every { useCaseRepository.findById(99L) } returns Optional.empty()

        assertThatThrownBy {
            service.createRequirement(Requirement(shortreq = "Requirement"), listOf(99L), emptyList(), 9L)
        }.isInstanceOf(RequirementManagementNotFound::class.java)
        verify(exactly = 0) { requirementService.createRequirement(any()) }
    }

    @Test
    fun `system protected use case cannot be renamed or deleted`() {
        val protected = UseCase(id = 3L, name = "System", systemProtected = true)
        every { useCaseRepository.findById(3L) } returns Optional.of(protected)

        assertThatThrownBy { service.updateUseCase(3L, "Other", 9L) }
            .isInstanceOf(RequirementManagementConflict::class.java)
        assertThatThrownBy { service.deleteUseCase(3L, 9L) }
            .isInstanceOf(RequirementManagementConflict::class.java)
    }

    @Test
    fun `assigned use case cannot be deleted`() {
        val useCase = UseCase(id = 3L, name = "Cloud")
        every { useCaseRepository.findById(3L) } returns Optional.of(useCase)
        every { useCaseRepository.countRequirementsByUseCaseId(3L) } returns 2L

        assertThatThrownBy { service.deleteUseCase(3L, 9L) }
            .isInstanceOf(RequirementManagementConflict::class.java)
            .hasMessageContaining("remove those assignments first")
        verify(exactly = 0) { useCaseRepository.delete(any()) }
    }

    @Test
    fun `frozen requirement delete is exposed as a conflict`() {
        every { requirementService.deleteRequirement(4L) } throws IllegalStateException("frozen in release 1.0")

        assertThatThrownBy { service.deleteRequirement(4L, 9L) }
            .isInstanceOf(RequirementManagementConflict::class.java)
            .hasMessageContaining("frozen")
    }
}
