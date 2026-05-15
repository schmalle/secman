package com.secman.domain

import io.micronaut.serde.annotation.Serdeable

/**
 * Lifecycle of an AiAnswerSuggestion row. Feature 088.
 *
 * APPLIED     — the live suggestion for (assessment, requirement). A Response
 *               row with this `aiSuggestionId` is the authoritative answer.
 * SUPERSEDED  — replaced by a later APPLIED row from a re-run. Kept for audit
 *               history; the linked Response (if any) was either deleted or
 *               relinked to the new APPLIED row.
 * FAILED      — call to OpenRouter or downstream validation failed. No Response
 *               is written for this requirement; the human answers manually.
 */
@Serdeable
enum class AiAnswerSuggestionStatus {
    APPLIED,
    SUPERSEDED,
    FAILED
}
