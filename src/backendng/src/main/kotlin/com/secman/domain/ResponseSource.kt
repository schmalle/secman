package com.secman.domain

import io.micronaut.serde.annotation.Serdeable

/**
 * Provenance of a Response row. Feature 088.
 *
 * MANUAL        — human authored.
 * AI_GENERATED  — written by AiSuggestionJobService from an APPLIED
 *                 AiAnswerSuggestion, never touched by a human.
 * AI_EDITED     — was AI_GENERATED, then a human modified answerType or
 *                 comment. Re-runs skip these unless force=true.
 */
@Serdeable
enum class ResponseSource {
    MANUAL,
    AI_GENERATED,
    AI_EDITED
}
