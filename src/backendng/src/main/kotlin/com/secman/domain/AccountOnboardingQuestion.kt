package com.secman.domain

import io.micronaut.serde.annotation.Serdeable
import jakarta.persistence.*
import jakarta.validation.constraints.NotBlank
import java.time.LocalDateTime

/**
 * How an [AccountOnboardingQuestion] is presented, and how many choices an answer may carry.
 *
 * [BOOLEAN] is deliberately not a separate storage shape: a boolean question is a question
 * with exactly two choices, so the rule matcher only ever sees choice ids and there is no
 * second code path to keep in step. The distinction is a rendering hint plus the same
 * "at most one" cardinality rule [SINGLE_SELECT] carries.
 */
@Serdeable
enum class OnboardingInputType {
    SINGLE_SELECT,
    MULTI_SELECT,
    BOOLEAN;

    /** True when an answer to this question may name more than one choice. */
    fun allowsMultiple(): Boolean = this == MULTI_SELECT
}

/**
 * One question put to the owner of a newly discovered AWS account in
 * [AccountOnboardingMode.GUIDED] onboarding.
 *
 * Questions carry no security meaning on their own. What an answer *means* is expressed
 * entirely by [AccountOnboardingRule], which maps a combination of [AccountOnboardingChoice]
 * rows to the use cases a risk assessment should be scoped to.
 *
 * [questionKey] is the stable identifier: labels are edited freely, but a rule and a stored
 * answer both reference choices, and an exported rule set is re-imported by key. Keys are
 * lowercase-kebab and immutable once rules reference the question's choices.
 */
@Entity
@Table(
    name = "account_onboarding_question",
    indexes = [
        Index(name = "idx_aob_question_order", columnList = "display_order")
    ]
)
@Serdeable
data class AccountOnboardingQuestion(
    @Id
    // IDENTITY, not the AUTO default: on MariaDB Hibernate maps AUTO to a native sequence
    // (<table>_seq) that knows nothing about rows the database numbered, which on a
    // long-lived schema hands out ids already taken. See docs/ARCHITECTURE.md.
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "question_key", nullable = false, unique = true, length = 64)
    @NotBlank
    var questionKey: String,

    @Column(nullable = false, length = 500)
    @NotBlank
    var label: String,

    @Column(name = "help_text", length = 1024)
    var helpText: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "input_type", nullable = false, length = 32)
    var inputType: OnboardingInputType = OnboardingInputType.SINGLE_SELECT,

    @Column(name = "display_order", nullable = false)
    var displayOrder: Int = 0,

    /** When true the owner cannot submit without answering. */
    @Column(nullable = false)
    var required: Boolean = true,

    /** Inactive questions are neither asked nor considered by the matcher. */
    @Column(nullable = false)
    var active: Boolean = true,

    @OneToMany(mappedBy = "question", fetch = FetchType.LAZY)
    @OrderBy("displayOrder ASC, id ASC")
    var choices: MutableList<AccountOnboardingChoice> = mutableListOf(),

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: LocalDateTime? = null,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime? = null
) {
    companion object {
        /** Keys are referenced by exported rule sets and stored answers, so keep them boring. */
        val KEY_PATTERN = Regex("^[a-z0-9][a-z0-9-]{0,63}$")
        const val MAX_QUESTIONS = 50
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
        "AccountOnboardingQuestion(id=$id, questionKey='$questionKey', inputType=$inputType, active=$active)"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AccountOnboardingQuestion) return false
        return id != null && id == other.id
    }

    override fun hashCode(): Int = id?.hashCode() ?: 0
}
