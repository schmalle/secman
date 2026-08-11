package com.secman.repository

import com.secman.domain.AccountOnboardingQuestion
import io.micronaut.data.annotation.Query
import io.micronaut.data.annotation.Repository
import io.micronaut.data.jpa.repository.JpaRepository
import java.util.Optional

@Repository
interface AccountOnboardingQuestionRepository : JpaRepository<AccountOnboardingQuestion, Long> {

    fun findByQuestionKeyIgnoreCase(questionKey: String): Optional<AccountOnboardingQuestion>

    fun findByActiveTrueOrderByDisplayOrderAscIdAsc(): List<AccountOnboardingQuestion>

    fun findAllByOrderByDisplayOrderAscIdAsc(): List<AccountOnboardingQuestion>

    /**
     * Active questions with their active choices, in display order.
     *
     * One query rather than a lazy walk: the questionnaire GET renders every question and
     * every choice, so the lazy collection would be an N+1 on an unauthenticated endpoint.
     */
    @Query(
        """
        SELECT DISTINCT q FROM AccountOnboardingQuestion q
        LEFT JOIN FETCH q.choices c
        WHERE q.active = true AND (c IS NULL OR c.active = true)
        ORDER BY q.displayOrder ASC, q.id ASC
        """
    )
    fun findActiveWithChoices(): List<AccountOnboardingQuestion>

    fun countByActiveTrue(): Long
}
