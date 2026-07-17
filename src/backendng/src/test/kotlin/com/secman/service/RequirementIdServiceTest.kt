package com.secman.service

import com.secman.domain.RequirementIdSequence
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("RequirementIdService")
class RequirementIdServiceTest {

    private lateinit var entityManager: EntityManager
    private lateinit var service: RequirementIdService

    @BeforeEach
    fun setUp() {
        entityManager = mockk()
        service = RequirementIdService(entityManager)
    }

    @Test
    fun `getNextId locks the sequence row with PESSIMISTIC_WRITE and increments it`() {
        val sequence = RequirementIdSequence(id = 1L, nextValue = 41)
        every {
            entityManager.find(RequirementIdSequence::class.java, 1L, LockModeType.PESSIMISTIC_WRITE)
        } returns sequence

        val id = service.getNextId()

        assertThat(id).isEqualTo("REQ-041")
        assertThat(sequence.nextValue).isEqualTo(42)
        // The lock mode is the point of the fix: a plain read here allowed two concurrent
        // requirement creations to issue the same REQ id.
        verify {
            entityManager.find(RequirementIdSequence::class.java, 1L, LockModeType.PESSIMISTIC_WRITE)
        }
    }

    @Test
    fun `getNextId fails when the sequence row is missing`() {
        every {
            entityManager.find(RequirementIdSequence::class.java, 1L, LockModeType.PESSIMISTIC_WRITE)
        } returns null

        assertThatThrownBy { service.getNextId() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("not initialized")
    }

    @Test
    fun `resetSequence sets the locked row back to 1`() {
        val sequence = RequirementIdSequence(id = 1L, nextValue = 500)
        every {
            entityManager.find(RequirementIdSequence::class.java, 1L, LockModeType.PESSIMISTIC_WRITE)
        } returns sequence

        service.resetSequence()

        assertThat(sequence.nextValue).isEqualTo(1)
    }

    @Test
    fun `formatId pads below 1000 and passes larger values through`() {
        assertThat(service.formatId(7)).isEqualTo("REQ-007")
        assertThat(service.formatId(999)).isEqualTo("REQ-999")
        assertThat(service.formatId(1000)).isEqualTo("REQ-1000")
    }
}
