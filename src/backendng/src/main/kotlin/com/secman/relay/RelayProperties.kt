package com.secman.relay

import io.micronaut.context.annotation.ConfigurationProperties
import io.micronaut.serde.annotation.Serdeable

/**
 * Configuration for the outbound relay publisher.
 *
 * The whole feature is off unless [enabled] is explicitly true — set it with
 * `SECMAN_RELAY_ENABLED=true`. That single switch is the "force secman to
 * establish a connection to an external server" control: with it unset, no
 * outbound connection is ever attempted and none of the `/api/relay/*`
 * endpoints do anything but report that the relay is disabled.
 *
 * The direction is fixed and is the core of the design: secman dials the relay.
 * The relay never dials secman, is never told where secman is, and holds no
 * secman credential. See docs/RELAY.md.
 *
 * Environment variable names follow Micronaut's usual mapping, e.g.
 * `secman.relay.publish-interval` <- `SECMAN_RELAY_PUBLISH_INTERVAL`.
 * The three secrets come from pass-cli in every real deployment.
 */
@ConfigurationProperties("secman.relay")
@Serdeable
data class RelayProperties(
    /** Master switch. Nothing outbound happens while this is false. */
    var enabled: Boolean = false,

    /**
     * Base URL of the relay's ingest plane, e.g. `https://relay.example.com`
     * or `https://10.0.1.7:9443` when the ingest listener is bound privately.
     * Paths (`/ingest/v1/...`) are appended by [RelayClient].
     */
    var url: String = "",

    /** Bearer credential the relay checks. Must match `RELAY_INGEST_TOKEN`. */
    var token: String = "",

    /**
     * Key for the per-request body signature. Must match `RELAY_INGEST_HMAC_KEY`.
     * Kept separate from [token] so that a leaked bearer token is not by itself
     * enough to forge a snapshot.
     */
    var hmacKey: String = "",

    /**
     * Stable identifier for this secman instance. The relay refuses a snapshot
     * whose instance id differs from the one it is already serving, so two
     * environments cannot interleave onto one phone screen.
     */
    var instanceId: String = "secman",

    /** How often the snapshot is pushed. */
    var publishInterval: String = "60s",

    /** Per-request timeout for the outbound call. */
    var timeoutSeconds: Long = 10,

    /**
     * Sections to include in the snapshot. Narrowing this list is the primary
     * data-minimisation control: whatever is not listed never leaves the
     * trusted network. Valid names are in [RelaySnapshotBuilder.ALL_SECTIONS].
     */
    var sections: List<String> = RelaySnapshotBuilder.ALL_SECTIONS,

    /**
     * Allow an `http://` relay URL. Off by default and intended only for a
     * local development relay; a production relay is either https end-to-end or
     * reached over a private link that the operator has separately assured.
     */
    var allowPlaintextUrl: Boolean = false
)
