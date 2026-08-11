package com.secman.repository

import com.secman.domain.AccountOnboardingChoice
import io.micronaut.data.annotation.Query
import io.micronaut.data.annotation.Repository
import io.micronaut.data.jpa.repository.JpaRepository
import java.util.Optional

@Repository
interface AccountOnboardingChoiceRepository : JpaRepository<AccountOnboardingChoice, Long> {

    /**
     * Explicit JPQL because the derived-name parser reads everything between OrderBy and the
     * trailing Asc as one property, so a two-property ordering cannot be expressed in the name.
     */
    @Query(
        """
        SELECT c FROM AccountOnboardingChoice c
        WHERE c.question.id = :questionId
        ORDER BY c.displayOrder ASC, c.id ASC
        """
    )
    fun findByQuestionIdOrderByDisplayOrderAscIdAsc(questionId: Long): List<AccountOnboardingChoice>

    fun findByQuestionIdAndChoiceKeyIgnoreCase(questionId: Long, choiceKey: String): Optional<AccountOnboardingChoice>

    fun countByQuestionId(questionId: Long): Long

    fun deleteByQuestionId(questionId: Long)

    /**
     * How many rules reference any choice of this question.
     *
     * Deleting a question out from under a rule would leave the rule matching a combination
     * nobody can submit, so the controller refuses with 409 while this is non-zero rather
     * than cascading the delete.
     */
    @Query(
        """
        SELECT COUNT(r) FROM AccountOnboardingRule r
        JOIN r.choices c
        WHERE c.question.id = :questionId
        """
    )
    fun countRulesReferencingQuestion(questionId: Long): Long

    /** How many rules reference this one choice. Same refusal rule as above. */
    @Query(
        """
        SELECT COUNT(r) FROM AccountOnboardingRule r
        JOIN r.choices c
        WHERE c.id = :choiceId
        """
    )
    fun countRulesReferencingChoice(choiceId: Long): Long
}
