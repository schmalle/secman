package com.secman.repository

import com.secman.domain.AccountOnboardingRule
import io.micronaut.data.annotation.Query
import io.micronaut.data.annotation.Repository
import io.micronaut.data.jpa.repository.JpaRepository
import java.util.Optional

@Repository
interface AccountOnboardingRuleRepository : JpaRepository<AccountOnboardingRule, Long> {

    fun findByNameIgnoreCase(name: String): Optional<AccountOnboardingRule>

    /**
     * Explicit JPQL because the derived-name parser reads everything between OrderBy and the
     * trailing Asc as one property, so a two-property ordering cannot be expressed in the name.
     */
    @Query(
        """
        SELECT r FROM AccountOnboardingRule r
        ORDER BY r.priorityOrder ASC, r.id ASC
        """
    )
    fun findAllByOrderByPriorityOrderAscIdAsc(): List<AccountOnboardingRule>

    /** Same two-property ordering as above, restricted to active rules. */
    @Query(
        """
        SELECT r FROM AccountOnboardingRule r
        WHERE r.active = true
        ORDER BY r.priorityOrder ASC, r.id ASC
        """
    )
    fun findByActiveTrueOrderByPriorityOrderAscIdAsc(): List<AccountOnboardingRule>

    fun findByActiveTrueAndIsDefaultTrue(): List<AccountOnboardingRule>

    fun findByIsDefaultTrue(): List<AccountOnboardingRule>

    fun countByActiveTrue(): Long

    /**
     * Rules that resolve to a given use case. Used before deleting a use case elsewhere and
     * by the admin UI's "what breaks if I remove this" view.
     */
    @Query(
        """
        SELECT r FROM AccountOnboardingRule r
        JOIN r.useCases u
        WHERE u.id = :useCaseId
        ORDER BY r.priorityOrder ASC, r.id ASC
        """
    )
    fun findByUseCaseId(useCaseId: Long): List<AccountOnboardingRule>
}
