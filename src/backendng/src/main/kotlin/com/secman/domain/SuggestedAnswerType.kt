package com.secman.domain

import io.micronaut.serde.annotation.Serdeable

/**
 * Answer types the AI is allowed to produce. Feature 088.
 *
 * UNKNOWN exists for AI use only — the model uses it to signal "not enough
 * evidence to decide". When UNKNOWN comes back, the AnswerType written to the
 * Response is null and the human is expected to answer manually.
 *
 * YES / NO / N_A map 1:1 to the existing [AnswerType] enum.
 */
@Serdeable
enum class SuggestedAnswerType {
    YES,
    NO,
    N_A,
    UNKNOWN;

    /**
     * Convert to the existing user-facing AnswerType. Returns null for UNKNOWN
     * (no Response row should be written with answerType=UNKNOWN).
     */
    fun toAnswerType(): AnswerType? = when (this) {
        YES -> AnswerType.YES
        NO -> AnswerType.NO
        N_A -> AnswerType.N_A
        UNKNOWN -> null
    }
}
