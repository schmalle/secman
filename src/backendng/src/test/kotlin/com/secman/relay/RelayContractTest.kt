package com.secman.relay

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Contract tests for the secman side of the mobile relay.
 *
 * The relay is a separate process in another language, so the things that can
 * silently diverge are the wire format and the authorization table. Both are
 * pinned here.
 */
class RelayContractTest {

    private val properties = RelayProperties(
        enabled = true,
        url = "https://relay.example.com",
        token = "an-ingest-token-that-is-long-ok!",
        hmacKey = "an-ingest-hmac-key-that-is-long1",
        instanceId = "secman-test"
    )

    private val client = RelayClient(properties, jacksonObjectMapper())

    // --- signature parity ----------------------------------------------------

    /**
     * A known-answer vector shared with the relay.
     *
     * The identical expectation lives in `TestSignPayloadCrossLanguageVector`
     * in `src/relay/internal/auth/auth_test.go`. If either side changes the
     * canonical string — the separators, the version prefix, or hashing the
     * body instead of its digest — exactly one of the two tests goes red, and
     * the alternative is an authentication failure that only shows up in a
     * deployment.
     */
    @Test
    fun `signature matches the cross-language vector`() {
        val key = "relay-parity-test-key-0123456789"
        val timestamp = 1_700_000_000L
        val nonce = "0123456789abcdef0123456789abcdef"
        val body = """{"schemaVersion":1,"instanceId":"secman","generatedAt":"2026-08-09T12:00:00Z","sections":{"totals":{"assets":3}}}"""

        val signature = client.signPayload(key, timestamp, nonce, body.toByteArray(Charsets.UTF_8))

        assertThat(signature).isEqualTo("v1=fc941aae10985d7899cc9f5c2de1805db682ddf75758674607b3415e3f696771")
    }

    @Test
    fun `signature changes when any signed component changes`() {
        val key = "relay-parity-test-key-0123456789"
        val body = """{"a":1}""".toByteArray(Charsets.UTF_8)
        val base = client.signPayload(key, 1_700_000_000L, "0123456789abcdef", body)

        assertThat(client.signPayload(key, 1_700_000_001L, "0123456789abcdef", body)).isNotEqualTo(base)
        assertThat(client.signPayload(key, 1_700_000_000L, "fedcba9876543210", body)).isNotEqualTo(base)
        assertThat(client.signPayload(key, 1_700_000_000L, "0123456789abcdef", """{"a":2}""".toByteArray())).isNotEqualTo(base)
        assertThat(client.signPayload("another-key-that-is-long-enough1", 1_700_000_000L, "0123456789abcdef", body)).isNotEqualTo(base)
    }

    // --- outbound configuration ---------------------------------------------

    @Test
    fun `a well-formed configuration validates`() {
        assertThat(client.validateConfiguration()).isNull()
    }

    /**
     * A leaked bearer token alone must not be enough to forge a snapshot, which
     * is only true if the two secrets are actually different.
     */
    @Test
    fun `token and hmac key must differ`() {
        val shared = "the-very-same-secret-used-twice1"
        val bad = RelayClient(properties.copy(token = shared, hmacKey = shared), jacksonObjectMapper())

        assertThat(bad.validateConfiguration()).contains("must be different")
    }

    @Test
    fun `plaintext relay url is refused unless explicitly allowed`() {
        val plaintext = properties.copy(url = "http://relay.example.com")

        assertThat(RelayClient(plaintext, jacksonObjectMapper()).validateConfiguration())
            .contains("must use https")

        // The escape hatch exists for a local development relay and says so.
        assertThat(
            RelayClient(plaintext.copy(allowPlaintextUrl = true), jacksonObjectMapper()).validateConfiguration()
        ).isNull()
    }

    @Test
    fun `credentials embedded in the relay url are refused`() {
        val withCredentials = properties.copy(url = "https://user:pass@relay.example.com")

        assertThat(RelayClient(withCredentials, jacksonObjectMapper()).validateConfiguration())
            .contains("must not contain credentials")
    }

    @Test
    fun `missing configuration is reported by name`() {
        assertThat(RelayClient(properties.copy(url = ""), jacksonObjectMapper()).validateConfiguration())
            .contains("secman.relay.url")
        assertThat(RelayClient(properties.copy(token = ""), jacksonObjectMapper()).validateConfiguration())
            .contains("secman.relay.token")
        assertThat(RelayClient(properties.copy(hmacKey = ""), jacksonObjectMapper()).validateConfiguration())
            .contains("secman.relay.hmac-key")
    }

    // --- the authorization table --------------------------------------------

    /**
     * Every publishable section must declare a role gate.
     *
     * The relay refuses a whole snapshot containing a section with no policy —
     * deliberately, because a section nobody can read is indistinguishable from
     * a broken app. This test makes that failure appear here instead.
     */
    @Test
    fun `every section has a role policy`() {
        assertThat(RelaySnapshotBuilder.SECTION_POLICIES.keys)
            .containsExactlyInAnyOrderElementsOf(RelaySnapshotBuilder.ALL_SECTIONS)

        RelaySnapshotBuilder.SECTION_POLICIES.forEach { (section, policy) ->
            assertThat(policy.requiredRoles)
                .`as`("section '%s' must require at least one role", section)
                .isNotEmpty()
        }
    }

    /**
     * The gates mirror the `@Secured` annotations on the controllers the data
     * comes from. Changing one without the other means a phone showing data the
     * web UI would refuse — nothing else in the build catches it.
     */
    @Test
    fun `section policies mirror the secman controllers`() {
        val policies = RelaySnapshotBuilder.SECTION_POLICIES

        // DashboardController: @Secured("ADMIN", "SECCHAMPION")
        assertThat(policies.getValue(RelaySnapshotBuilder.SECTION_KPIS).requiredRoles)
            .containsExactlyInAnyOrder("ADMIN", "SECCHAMPION")

        // VulnerabilityExceptionRequestController.getPendingCount: same
        assertThat(policies.getValue(RelaySnapshotBuilder.SECTION_EXCEPTIONS).requiredRoles)
            .containsExactlyInAnyOrder("ADMIN", "SECCHAMPION")

        // CrowdStrikeController: ADMIN / VULN
        assertThat(policies.getValue(RelaySnapshotBuilder.SECTION_IMPORTS).requiredRoles)
            .containsExactlyInAnyOrder("ADMIN", "VULN")

        // The admin summary is ADMIN-only.
        for (section in listOf(
            RelaySnapshotBuilder.SECTION_TOTALS,
            RelaySnapshotBuilder.SECTION_TOP_PRODUCTS,
            RelaySnapshotBuilder.SECTION_TOP_SERVERS
        )) {
            assertThat(policies.getValue(section).requiredRoles).containsExactly("ADMIN")
        }
    }

    /**
     * The relay validates section names against `[a-z0-9-]` before they reach a
     * URL or a scope string, so a name it would refuse produces a section that
     * can never be read.
     */
    @Test
    fun `section names satisfy the relay's character class`() {
        val valid = Regex("^[a-z0-9]([a-z0-9-]*[a-z0-9])?$")
        RelaySnapshotBuilder.ALL_SECTIONS.forEach { section ->
            assertThat(valid.matches(section))
                .`as`("section name '%s' must be lowercase letters, digits and '-'", section)
                .isTrue()
        }
    }

    /**
     * Every role named in the table must exist in secman's vocabulary. The
     * relay rejects an unknown role, and a typo would otherwise produce a
     * section that silently matches nobody.
     */
    @Test
    fun `policy roles are real secman roles`() {
        val known = com.secman.domain.User.Role.entries.map { it.name }.toSet()

        RelaySnapshotBuilder.SECTION_POLICIES.forEach { (section, policy) ->
            assertThat(known)
                .`as`("section '%s' names a role secman does not define", section)
                .containsAll(policy.requiredRoles)
        }
    }

    // --- schema versions ------------------------------------------------------

    /**
     * Both sides carry a version and both refuse a mismatch rather than
     * best-effort parsing it. Keep these in step with the constants in
     * `src/relay/internal/model/model.go`.
     */
    @Test
    fun `schema versions match the relay`() {
        assertThat(RELAY_SNAPSHOT_SCHEMA_VERSION).isEqualTo(2)
        assertThat(RELAY_CONTROL_SCHEMA_VERSION).isEqualTo(2)
    }

    @Test
    fun `timestamps render as the RFC 3339 form the relay parses`() {
        val rendered = RelaySnapshotBuilder.rfc3339(java.time.Instant.parse("2026-08-09T12:00:00Z"))

        assertThat(rendered).isEqualTo("2026-08-09T12:00:00Z")
        assertThat(java.time.Instant.parse(rendered)).isNotNull()
    }
}

/**
 * Enrollment-code generation.
 *
 * The end-to-end enrollment path is exercised by the relay's own suite; what
 * matters here is that the code is unguessable and typeable, and that only its
 * digest ever leaves this process.
 */
class RelayEnrollmentCodeTest {

    private val service = RelayEnrollmentService(
        properties = RelayProperties(),
        publisher = mockk(relaxed = true),
        principalService = mockk(relaxed = true),
        userRepository = mockk(relaxed = true)
    )

    @Test
    fun `generated codes are grouped, unambiguous and unpredictable`() {
        val codes = (1..200).map { service.generateCode() }

        codes.forEach { code ->
            // Four groups of five, e.g. 7K2QX-3MNPB-...
            assertThat(code).matches("^[0-9A-HJKMNP-TV-Z]{5}(-[0-9A-HJKMNP-TV-Z]{5}){3}$")
            // I, L, O and U are excluded: the code is read off a screen and
            // typed into a phone, and a misread character is a support ticket.
            assertThat(code).doesNotContain("I").doesNotContain("L")
            assertThat(code).doesNotContain("O").doesNotContain("U")
        }
        // ~100 bits of entropy; a collision in 200 draws would mean the
        // generator is not what it claims to be.
        assertThat(codes.toSet()).hasSize(codes.size)
    }

    @Test
    fun `the digest pushed to the relay is a lowercase hex sha256`() {
        // Known answer: sha256("abc"). secman never stores the plaintext, so
        // this digest is the only thing the relay ever sees.
        assertThat(service.sha256Hex("abc"))
            .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
    }
}
