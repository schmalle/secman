package com.secman.relay

import java.security.MessageDigest

/**
 * The one place the relay code computes a SHA-256.
 *
 * ## Why this is not an A02 violation, and why it is in a single file
 *
 * `./scripts/owasp-check.sh` flags every `MessageDigest.getInstance("SHA-256")`
 * in Kotlin as **A02-weak-hash**, because in this codebase that call has almost
 * always meant "hashing a secret", and secrets must use
 * `BCryptPasswordEncoder`. That rule is right about passwords and about the
 * legacy API-key path in `McpAuthenticationService`. It is a syntactic
 * heuristic, though, and it cannot tell those apart from a plain message
 * digest — so the three uses below are consolidated here, where the
 * justification can be read once instead of being argued three times.
 *
 * The three call sites:
 *
 * 1. **[digestOf] inside the ingest signature** ([RelayClient.signPayload]).
 *    The HMAC canonical string is `v1:<ts>:<nonce>:<hex sha256(body)>`. This is
 *    a message digest inside an HMAC construction, exactly as the protocol
 *    specifies; there is no secret being stored. BCrypt here would not be
 *    "stronger", it would be a different, non-interoperable protocol — the Go
 *    relay computes the same digest and the two must agree byte for byte.
 *
 * 2. **[digestOf] for the principal-list fingerprint**
 *    ([RelayPrincipalService.digest]). A change-detection tag over usernames and
 *    role names, used to avoid re-pushing an unchanged authorization table. No
 *    secret is involved at all.
 *
 * 3. **[hashEnrollmentCode]** — the only one that hashes something secret, and
 *    the only one worth arguing about. See below.
 *
 * ## Why an enrollment code is hashed with SHA-256 and not BCrypt
 *
 * BCrypt exists to make *offline enumeration* expensive for values a human
 * chose, where the search space is small. An enrollment code is not that: it is
 * 20 characters over a 32-symbol alphabet drawn from `SecureRandom`
 * (~100 bits), single use, and dead within 24 hours (15 minutes by default). At
 * 100 bits of entropy a work factor buys nothing an attacker would notice —
 * they are not enumerating that space at any cost per guess.
 *
 * Two concrete reasons BCrypt would make this *worse*:
 *
 *  - **It breaks the lookup.** BCrypt salts per hash, so the relay could not
 *    look a presented code up by digest; it would have to run BCrypt against
 *    every pending grant in turn. That turns an O(1) map read into an O(n)
 *    scan of a deliberately slow function on an unauthenticated endpoint — a
 *    denial-of-service primitive handed to anyone who can POST.
 *  - **It is the wrong tool for a high-entropy token.** The same reasoning is
 *    why session identifiers and API tokens are stored as digests industry-wide,
 *    rather than as password hashes.
 *
 * What actually protects the code is its entropy, its single use, and its short
 * life — plus the fact that secman never stores the plaintext at all and the
 * relay only ever holds this digest. Neither a secman database dump nor a relay
 * compromise yields a usable code.
 *
 * If the rule is ever narrowed to distinguish digest use from secret storage,
 * this file needs no change; only the scanner's expectation does.
 */
internal object RelayDigest {

    /** Lowercase hex SHA-256 of [value]. */
    fun hexOf(value: String): String = hex(digestOf(value.toByteArray(Charsets.UTF_8)))

    /** Raw SHA-256 of [bytes]. */
    fun digestOf(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)

    /**
     * The digest of an enrollment code, as pushed to the relay.
     *
     * Named for its purpose so the call site reads as a deliberate choice
     * rather than an incidental hash. See the class comment for why this is
     * SHA-256.
     */
    fun hashEnrollmentCode(code: String): String = hexOf(code)

    /** A fresh, incrementally-fed digest, for hashing a structure field by field. */
    fun newAccumulator(): MessageDigest = MessageDigest.getInstance("SHA-256")

    /** Lowercase hex rendering, the encoding both sides of the contract use. */
    fun hex(bytes: ByteArray): String {
        val out = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            out.append(HEX[(b.toInt() shr 4) and 0x0F])
            out.append(HEX[b.toInt() and 0x0F])
        }
        return out.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
