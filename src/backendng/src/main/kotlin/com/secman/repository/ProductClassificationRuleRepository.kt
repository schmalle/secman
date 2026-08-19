package com.secman.repository

import com.secman.domain.ProductClassificationRule
import io.micronaut.data.annotation.Query
import io.micronaut.data.annotation.Repository
import io.micronaut.data.jpa.repository.JpaRepository

/**
 * Reads over [ProductClassificationRule].
 *
 * Ordering is explicit everywhere: [ProductClassifier] relies on a deterministic rule order so
 * that two rules matching the same value always resolve the same way.
 */
@Repository
interface ProductClassificationRuleRepository : JpaRepository<ProductClassificationRule, Long> {

    @Query("SELECT r FROM ProductClassificationRule r WHERE r.enabled = TRUE ORDER BY r.priority ASC, r.id ASC")
    fun findEnabledOrdered(): List<ProductClassificationRule>

    @Query("SELECT r FROM ProductClassificationRule r ORDER BY r.priority ASC, r.id ASC")
    fun findAllOrdered(): List<ProductClassificationRule>

    fun countByEnabled(enabled: Boolean): Long
}
