package com.secman.domain

import io.micronaut.serde.annotation.Serdeable

/**
 * Scope of an AI pre-fill run. Feature 088.
 *
 * WHOLE_ASSESSMENT       — every requirement attached to the assessment.
 * SUBSET                 — explicit list of requirementIds (e.g. filter by norm
 *                          in the modal).
 * SINGLE_REQUIREMENT     — exactly one requirement, used for retries.
 */
@Serdeable
enum class AiSuggestionScope {
    WHOLE_ASSESSMENT,
    SUBSET,
    SINGLE_REQUIREMENT
}
