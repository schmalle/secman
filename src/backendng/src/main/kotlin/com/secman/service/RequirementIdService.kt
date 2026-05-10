package com.secman.service

import com.secman.repository.RequirementIdSequenceRepository
import jakarta.inject.Inject
import jakarta.inject.Singleton
import jakarta.transaction.Transactional

@Singleton
open class RequirementIdService(
    @Inject private val sequenceRepository: RequirementIdSequenceRepository
) {

    @Transactional
    open fun getNextId(): String {
        val sequence = sequenceRepository.findByIdForUpdate(1L)
            .orElseThrow { IllegalStateException("Requirement ID sequence not initialized. Run database migrations.") }
        val nextVal = sequence.nextValue
        sequence.nextValue = nextVal + 1
        sequenceRepository.update(sequence)
        return formatId(nextVal)
    }

    /**
     * Reset the sequence so the next issued ID is REQ-001 again.
     * Intended for callers that have just deleted all requirements
     * (e.g. the admin "Delete All Requirements" action).
     */
    @Transactional
    open fun resetSequence() {
        val sequence = sequenceRepository.findByIdForUpdate(1L)
            .orElseThrow { IllegalStateException("Requirement ID sequence not initialized. Run database migrations.") }
        sequence.nextValue = 1
        sequenceRepository.update(sequence)
    }

    fun formatId(num: Int): String {
        return if (num < 1000) {
            "REQ-${num.toString().padStart(3, '0')}"
        } else {
            "REQ-$num"
        }
    }
}
