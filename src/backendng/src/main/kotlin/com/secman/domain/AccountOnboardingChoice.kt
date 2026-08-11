package com.secman.domain

import io.micronaut.serde.annotation.Serdeable
import jakarta.persistence.*
import jakarta.validation.constraints.NotBlank
import java.time.LocalDateTime

/**
 * One selectable answer to an [AccountOnboardingQuestion].
 *
 * This is the atom the rule engine works in: an [AccountOnboardingRule] is a set of choices,
 * and a submitted answer is a set of choices. Everything the matcher does is set containment
 * over these ids — see [com.secman.service.AccountOnboardingRuleMatcher].
 *
 * [choiceKey] is unique *per question*, not globally, so two questions can both offer a
 * choice keyed `yes` without collision.
 */
@Entity
@Table(
    name = "account_onboarding_choice",
    uniqueConstraints = [
        UniqueConstraint(name = "uk_aob_choice_question_key", columnNames = ["question_id", "choice_key"])
    ],
    indexes = [
        Index(name = "idx_aob_choice_question", columnList = "question_id")
    ]
)
@Serdeable
data class AccountOnboardingChoice(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    // EAGER on purpose. A choice is almost never useful without its question — the rule engine
    // renders `questionKey=choiceKey`, the admin editor groups by question, and the dry-run
    // matrix prints both. Several of those call sites run outside a transaction, where a lazy
    // proxy would throw instead of loading one cheap row.
    @ManyToOne(optional = false, fetch = FetchType.EAGER)
    @JoinColumn(name = "question_id", nullable = false)
    var question: AccountOnboardingQuestion,

    @Column(name = "choice_key", nullable = false, length = 64)
    @NotBlank
    var choiceKey: String,

    @Column(nullable = false, length = 500)
    @NotBlank
    var label: String,

    @Column(name = "display_order", nullable = false)
    var displayOrder: Int = 0,

    /** Inactive choices are neither offered nor matched, but stay referenced by past answers. */
    @Column(nullable = false)
    var active: Boolean = true,

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: LocalDateTime? = null,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime? = null
) {
    companion object {
        val KEY_PATTERN = Regex("^[a-z0-9][a-z0-9-]{0,63}$")
        const val MAX_CHOICES_PER_QUESTION = 50
    }

    @PrePersist
    fun onCreate() {
        val now = LocalDateTime.now()
        createdAt = now
        updatedAt = now
    }

    @PreUpdate
    fun onUpdate() {
        updatedAt = LocalDateTime.now()
    }

    override fun toString(): String =
        "AccountOnboardingChoice(id=$id, choiceKey='$choiceKey', active=$active)"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AccountOnboardingChoice) return false
        return id != null && id == other.id
    }

    override fun hashCode(): Int = id?.hashCode() ?: 0
}
