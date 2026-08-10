package com.secman.relay

import io.micronaut.serde.annotation.Serdeable

/**
 * Wire types for the secman -> relay contract.
 *
 * Every timestamp on this contract is a **string**, not a temporal type. Two
 * reasons: the injected `ObjectMapper` in this codebase is a plain
 * `jacksonObjectMapper()` with no JavaTimeModule registered, and the consumer is
 * a Go process — an explicit RFC 3339 string is the one representation both
 * sides agree on without configuration. See [RelaySnapshotBuilder.rfc3339].
 */

/** Envelope version understood by the relay. Bump on a breaking change. */
const val RELAY_SNAPSHOT_SCHEMA_VERSION = 2

/** Envelope version of the control document. */
const val RELAY_CONTROL_SCHEMA_VERSION = 2

/**
 * The pushed status snapshot.
 *
 * [sections] is deliberately `Map<String, Any>`: the relay treats a section as
 * opaque JSON and re-serves it byte for byte, so adding a widget to the mobile
 * app never requires a relay release.
 *
 * [policy] is the part the relay *does* read. It carries, per section, the same
 * roles the secman controller that produced the data demands in its `@Secured`
 * annotation. That is what makes the phone show exactly what the web UI would:
 * secman states the rule, the relay enforces it, and neither invents one.
 */
@Serdeable
data class RelaySnapshot(
    val schemaVersion: Int = RELAY_SNAPSHOT_SCHEMA_VERSION,
    val instanceId: String,
    val generatedAt: String,
    val sections: Map<String, Any>,
    val policy: Map<String, RelaySectionPolicy>
)

/** The role gate for one section. Any-of semantics, exactly like `@Secured`. */
@Serdeable
data class RelaySectionPolicy(
    val requiredRoles: List<String>,
    val description: String? = null
)

/**
 * The pushed authorisation document.
 *
 * The three parts have deliberately different merge semantics on the relay:
 *
 *  - [principals] are replaced wholesale when [principalsAuthoritative] is set,
 *    because roles must be able to *shrink* — a user demoted in secman has to
 *    lose the matching sections on their phone.
 *  - [enrollments] are additive and expire on their own.
 *  - [revocations] are additive and permanent; omitting one never undoes it.
 */
@Serdeable
data class RelayControl(
    val schemaVersion: Int = RELAY_CONTROL_SCHEMA_VERSION,
    val instanceId: String,
    val issuedAt: String,
    val principalsAuthoritative: Boolean = false,
    val principals: List<RelayPrincipal> = emptyList(),
    val enrollments: List<RelayEnrollmentGrant> = emptyList(),
    val revocations: List<RelayRevocation> = emptyList()
)

/**
 * A secman user as the relay sees them: a stable subject, the roles secman says
 * they hold right now, and the external logins that map to them.
 *
 * No credential of any kind travels here. The relay cannot authenticate anyone
 * from this record — it can only decide, once a provider has proved an identity,
 * whether that identity corresponds to a secman user and what that user may see.
 */
@Serdeable
data class RelayPrincipal(
    val subject: String,
    val displayName: String? = null,
    val roles: List<String>,
    val identities: List<RelayExternalIdentity> = emptyList(),
    val disabled: Boolean = false
)

/** One external login bound to a principal. */
@Serdeable
data class RelayExternalIdentity(
    val provider: String,
    val subject: String,
    val label: String? = null
)

/**
 * Authorisation for exactly one device enrollment by code.
 *
 * Only the SHA-256 of the code travels. secman shows the plaintext to the admin
 * once and never stores it, so neither a relay compromise nor a secman database
 * dump yields a usable enrollment code.
 */
@Serdeable
data class RelayEnrollmentGrant(
    val codeSha256: String,
    val subject: String,
    val scopes: List<String>,
    val expiresAt: String,
    val label: String? = null
)

/** Revokes one device, or every device at once. */
@Serdeable
data class RelayRevocation(
    val deviceId: String? = null,
    val revokeAll: Boolean = false,
    val revokedAt: String,
    val reason: String? = null
)

// --- admin API request/response shapes --------------------------------------

/** Body of `POST /api/relay/enrollments`. */
@Serdeable
data class CreateRelayEnrollmentRequest(
    /** The secman username the code enrols a device for. */
    val subject: String,
    /** Sections the device may read: `status:*` or `status:<section>`. */
    val scopes: List<String> = listOf("status:*"),
    /** Lifetime of the code in minutes. Bounded by RelayEnrollmentService. */
    val ttlMinutes: Long? = null,
    /** Free-text hint shown in the device list. */
    val label: String? = null
)

/**
 * Response to an enrollment request.
 *
 * [code] is the only time the plaintext exists outside the admin's screen: it
 * is not persisted and cannot be retrieved again.
 */
@Serdeable
data class CreateRelayEnrollmentResponse(
    val code: String,
    val subject: String,
    val scopes: List<String>,
    val expiresAt: String
)

/** Body of `POST /api/relay/revocations`. */
@Serdeable
data class CreateRelayRevocationRequest(
    val deviceId: String? = null,
    val revokeAll: Boolean = false,
    val reason: String? = null
)

/** Body of `POST /api/relay/identities`. */
@Serdeable
data class CreateRelayIdentityRequest(
    /** secman username to bind the external account to. */
    val username: String,
    /** `apple`, `google` or `github`. */
    val provider: String,
    /**
     * The provider's stable account identifier — Apple's / Google's `sub`, or
     * GitHub's numeric account id. Never an email or a login name.
     */
    val providerSubject: String,
    val label: String? = null
)

/** One row of `GET /api/relay/identities`. */
@Serdeable
data class RelayIdentityResponse(
    val id: Long,
    val username: String,
    val provider: String,
    val providerSubject: String,
    val label: String?,
    val createdAt: String,
    val createdBy: String?
)

/** Local publisher state, plus whatever the relay reported on the last poll. */
@Serdeable
data class RelayStatusResponse(
    val enabled: Boolean,
    val url: String?,
    val instanceId: String,
    val sections: List<String>,
    val lastAttemptAt: String? = null,
    val lastSuccessAt: String? = null,
    val lastError: String? = null,
    val consecutiveFailures: Int = 0,
    val pushesAttempted: Long = 0,
    val pushesSucceeded: Long = 0,
    val principalsPublished: Int = 0,
    val lastPrincipalPushAt: String? = null,
    /** Raw body of the relay's `GET /ingest/v1/status`, or null if unreachable. */
    val relay: Map<String, Any>? = null,
    val relayError: String? = null
)

/** Outcome of one outbound call, never thrown. */
data class RelayCallResult(
    val success: Boolean,
    val statusCode: Int? = null,
    val body: String? = null,
    val error: String? = null
) {
    companion object {
        fun ok(statusCode: Int, body: String) = RelayCallResult(true, statusCode, body, null)
        fun failed(message: String, statusCode: Int? = null) = RelayCallResult(false, statusCode, null, message)
    }
}
