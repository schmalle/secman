package com.secman.domain

import io.micronaut.serde.annotation.Serdeable
import jakarta.persistence.*
import jakarta.validation.constraints.NotBlank
import java.time.LocalDateTime

/**
 * Maps a *combination* of answers to the use cases a risk assessment should be scoped to.
 *
 * The condition is a flat AND over [choices]: the rule matches iff every choice it names was
 * submitted. Choices may span several questions — that is what makes it a combination rather
 * than a per-question lookup. Nothing here is recursive and there are no operators, which is
 * exactly why this is a normalized set and not a `ruleJson` blob like
 * [DemandClassificationRule]: the rule → [UseCase] link needs real referential integrity, and
 * "which rules reference this choice" must stay a query.
 *
 * **Every matching rule contributes — nothing wins.** All matching active rules are unioned
 * and deduplicated into one assessment. [priorityOrder] exists only to give the admin UI a
 * stable, meaningful display order; it does not decide anything.
 *
 * [isDefault] marks the one rule that applies when *no* other rule matched. It is the only
 * rule allowed to name zero choices, and at most one may exist. Without it, an owner whose
 * answers match nothing is told a security champion will follow up rather than being handed
 * an empty questionnaire.
 */
@Entity
@Table(
    name = "account_onboarding_rule",
    indexes = [
        Index(name = "idx_aob_rule_active", columnList = "active"),
        Index(name = "idx_aob_rule_order", columnList = "priority_order")
    ]
)
@Serdeable
data class AccountOnboardingRule(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(nullable = false, unique = true, length = 255)
    @NotBlank
    var name: String,

    @Column(length = 1024)
    var description: String? = null,

    @Column(nullable = false)
    var active: Boolean = true,

    /** Display order only. Matching is a union — this never decides which rule applies. */
    @Column(name = "priority_order", nullable = false)
    var priorityOrder: Int = 0,

    /** The single fallback rule, applied only when nothing else matched. */
    @Column(name = "is_default", nullable = false)
    var isDefault: Boolean = false,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by")
    var createdBy: User? = null,

    /** The combination. Empty is legal only when [isDefault]. */
    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(
        name = "account_onboarding_rule_choice",
        joinColumns = [JoinColumn(name = "rule_id")],
        inverseJoinColumns = [JoinColumn(name = "choice_id")]
    )
    var choices: MutableSet<AccountOnboardingChoice> = mutableSetOf(),

    /** What this combination means. Never empty — a rule resolving to nothing is a trap. */
    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(
        name = "account_onboarding_rule_usecase",
        joinColumns = [JoinColumn(name = "rule_id")],
        inverseJoinColumns = [JoinColumn(name = "usecase_id")]
    )
    var useCases: MutableSet<UseCase> = mutableSetOf(),

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: LocalDateTime? = null,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime? = null
) {
    companion object {
        const val MAX_RULES = 200
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

    /**
     * True when this rule's combination is fully contained in [submittedChoiceIds].
     *
     * Set containment is the whole semantic: for SINGLE_SELECT questions the submitted set
     * holds one choice per question, so containment is exactly "AND across questions"; for
     * MULTI_SELECT it additionally allows a rule to be satisfied by one of several choices
     * the owner ticked on the same question.
     *
     * The default rule never matches here — it is applied by the matcher only after every
     * other rule has failed.
     */
    fun matches(submittedChoiceIds: Set<Long>): Boolean {
        if (isDefault) return false
        if (choices.isEmpty()) return false
        return choices.all { it.id != null && it.active && submittedChoiceIds.contains(it.id) }
    }

    override fun toString(): String =
        "AccountOnboardingRule(id=$id, name='$name', active=$active, isDefault=$isDefault, choices=${choices.size}, useCases=${useCases.size})"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AccountOnboardingRule) return false
        return id != null && id == other.id
    }

    override fun hashCode(): Int = id?.hashCode() ?: 0
}
