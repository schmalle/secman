package com.secman.domain

import io.micronaut.serde.annotation.Serdeable
import jakarta.persistence.*
import jakarta.validation.constraints.NotBlank
import java.security.SecureRandom
import java.time.LocalDateTime

@Serdeable
enum class InviteStatus {
    /** Mailed, not yet answered, not yet expired. The only state a submission may claim. */
    PENDING,

    /** Answered. The risk assessment was created and [AccountOnboardingInvite.riskAssessment] points at it. */
    SUBMITTED,

    /** The link lapsed before it was used. Terminal; surfaces as pending work for a SECCHAMPION. */
    EXPIRED,

    /** Withdrawn by an admin. Terminal. */
    CANCELLED
}

/**
 * A one-time link mailed to the owner of a newly discovered AWS account, letting them scope
 * their own risk assessment ([AccountOnboardingMode.GUIDED]).
 *
 * Modelled on [AssessmentToken], but held to a stricter bar in three places, because this
 * token *creates* a risk assessment rather than opening one that already exists:
 *
 * 1. **Entropy.** 256 bits from [SecureRandom], hex-encoded — not `UUID.randomUUID()`
 *    (122 bits, what [AssessmentToken] uses). [AssessmentToken] is deliberately left alone;
 *    this is not a reason to weaken the new one to match.
 * 2. **Expiry.** Days, not the 30 [AssessmentToken] grants — see
 *    `secman.account-onboarding.invite-expiry-days`.
 * 3. **Single use is enforced by the database**, not by reading [status] and then writing it.
 *    See `AccountOnboardingInviteRepository.claim`, a guarded UPDATE claimed *before* the
 *    assessment is created, so two concurrent submissions cannot both win.
 *
 * The token is a credential. It is never logged in full (callers log `token.take(8) + "…"`),
 * never returned by an admin API, and never minted during a dry run.
 */
@Entity
@Table(
    name = "account_onboarding_invite",
    indexes = [
        Index(name = "idx_aob_invite_lookup", columnList = "aws_account_id,owner_email,status"),
        Index(name = "idx_aob_invite_expiry", columnList = "expires_at")
    ]
)
@Serdeable
data class AccountOnboardingInvite(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(nullable = false, unique = true, length = 64)
    @NotBlank
    var token: String,

    @Column(name = "aws_account_id", nullable = false, length = 12)
    @NotBlank
    var awsAccountId: String,

    @Column(name = "owner_email", nullable = false, length = 255)
    @NotBlank
    var ownerEmail: String,

    @Column(name = "expires_at", nullable = false)
    var expiresAt: LocalDateTime,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    var status: InviteStatus = InviteStatus.PENDING,

    @Column(name = "used_at")
    var usedAt: LocalDateTime? = null,

    /**
     * The owner's answers as `[{"questionKey":"…","choiceKeys":["…"]}]`.
     *
     * Persisted even when rule resolution failed, which is the point: a submission that
     * matched no rule is not thrown away, so an admin can add the missing rule and the
     * owner's original link still works.
     */
    @Column(name = "answers_json", columnDefinition = "TEXT")
    var answersJson: String? = null,

    /** Comma-joined names of the rules that matched, for the audit trail. */
    @Column(name = "resolved_rules", length = 1024)
    var resolvedRules: String? = null,

    /** Days from submission to the assessment's deadline. Carried from the import that minted this. */
    @Column(name = "deadline_days", nullable = false)
    var deadlineDays: Int = 7,

    /** True when minted by the simulate surface. Makes test rows identifiable and sweepable. */
    @Column(nullable = false)
    var simulated: Boolean = false,

    /** When the "your link is about to expire" nudge was sent. NULL = not yet sent. */
    @Column(name = "reminder_sent_at")
    var reminderSentAt: LocalDateTime? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "risk_assessment_id")
    var riskAssessment: RiskAssessment? = null,

    /**
     * The ADMIN/SECCHAMPION whose import or simulation minted this.
     *
     * EAGER because the submission path reads it after its loading transaction has closed, to
     * carry the original requestor onto the created assessment. [riskAssessment] stays LAZY —
     * it is only ever *assigned*, inside a transaction, and eager-loading it would pull the
     * whole assessment graph on every token lookup.
     */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "requestor_id")
    var requestor: User? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: LocalDateTime? = null,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime? = null
) {
    companion object {
        private val RANDOM = SecureRandom()

        /** Token length in bytes before hex encoding. 32 bytes = 256 bits = 64 hex characters. */
        const val TOKEN_BYTES = 32

        /** What a well-formed token looks like. Anything else is refused without a lookup. */
        val TOKEN_PATTERN = Regex("^[a-f0-9]{64}$")

        const val DEFAULT_EXPIRY_DAYS = 14
        const val MIN_EXPIRY_DAYS = 1
        const val MAX_EXPIRY_DAYS = 90

        /** 256 bits of [SecureRandom], lowercase hex. See the class doc for why not UUID. */
        fun generateToken(): String {
            val bytes = ByteArray(TOKEN_BYTES)
            RANDOM.nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }

        /**
         * What is safe to write to a log or an error message. The full token is a credential:
         * a log line carrying one hands whoever can read the log the ability to create a risk
         * assessment as that account's owner.
         */
        fun redact(token: String?): String =
            if (token.isNullOrBlank()) "<none>" else token.take(8) + "…"
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
     * Read-side validity check. Never the authority for a *write* — that is
     * `AccountOnboardingInviteRepository.claim`, which decides atomically. This exists so a
     * GET can render "invalid or expired" without racing anything.
     */
    fun isUsable(now: LocalDateTime = LocalDateTime.now()): Boolean =
        status == InviteStatus.PENDING && now.isBefore(expiresAt)

    /** Last 4 digits only. The public questionnaire must not confirm a full account id to a stranger. */
    fun maskedAccountId(): String =
        if (awsAccountId.length <= 4) "****" else "****" + awsAccountId.takeLast(4)

    override fun toString(): String =
        "AccountOnboardingInvite(id=$id, token=${redact(token)}, awsAccountId='$awsAccountId', status=$status)"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AccountOnboardingInvite) return false
        return id != null && id == other.id
    }

    override fun hashCode(): Int = id?.hashCode() ?: 0
}
