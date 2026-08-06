package com.secman.service

import com.secman.domain.RequirementIdSequence
import jakarta.inject.Inject
import jakarta.inject.Singleton
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import jakarta.transaction.Transactional

@Singleton
open class RequirementIdService(
    @Inject private val entityManager: EntityManager
) {

    @Transactional
    open fun getNextId(): String {
        // PESSIMISTIC_WRITE (SELECT ... FOR UPDATE) serializes concurrent callers on the
        // sequence row. The previous repository read was a plain SELECT despite being named
        // "findByIdForUpdate", so two concurrent requirement creations could both read the
        // same nextValue and issue the same REQ-xxx id (then collide on the internal_id
        // unique constraint).
        val sequence = lockSequenceRow()
        val nextVal = sequence.nextValue
        sequence.nextValue = nextVal + 1
        return formatId(nextVal)
    }

    /**
     * Reset the sequence so the next issued ID is REQ-001 again.
     * Intended for callers that have just deleted all requirements
     * (e.g. the admin "Delete All Requirements" action).
     */
    @Transactional
    open fun resetSequence() {
        val sequence = lockSequenceRow()
        sequence.nextValue = 1
    }

    private fun lockSequenceRow(): RequirementIdSequence {
        return entityManager.find(RequirementIdSequence::class.java, 1L, LockModeType.PESSIMISTIC_WRITE)
            ?: throw IllegalStateException("Requirement ID sequence not initialized. Run database migrations.")
    }

    fun formatId(num: Int): String {
        return if (num < 1000) {
            "REQ-${num.toString().padStart(3, '0')}"
        } else {
            "REQ-$num"
        }
    }
}
