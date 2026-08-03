package com.secman.domain

/**
 * The third, orthogonal axis of the exception model, alongside
 * [VulnerabilityException.Subject] (WHAT) and [VulnerabilityException.Scope] (WHERE).
 *
 * Deliberately a separate top-level enum rather than a new `Subject` or `Scope` value:
 *
 *  - It spans both [VulnerabilityException] and [VulnerabilityExceptionRequest], and a
 *    NO_EDR row is not a "kind of vulnerability exception" — it is a different statement
 *    entirely.
 *  - A new `Subject`/`Scope` value would break every exhaustive `when` over those enums
 *    (~15 of them) and, far worse, would inherit vulnerability-suppression semantics by
 *    default. A new axis inherits nothing; suppression is opt-in via [VULNERABILITY].
 *
 * Only [VULNERABILITY] ever suppresses a finding. That invariant is enforced in three
 * synchronized places which MUST agree — see [VulnerabilityException.matches],
 * [com.secman.repository.ExceptionMatchSql.EXCEPTION_MATCH] and
 * [com.secman.service.ExceptionMatchIndex] — and is asserted by
 * `ExceptionMatchIndexTest` and `ExceptedFlagSqlAgreementIntegrationTest`.
 */
enum class ExceptionKind {
    /**
     * The historical meaning: suppress vulnerabilities matching this exception's
     * subject × scope. Sets `vulnerability.excepted = 1` via the materialization path.
     */
    VULNERABILITY,

    /**
     * "This asset cannot run an EDR agent." Always `scope = ASSET`; `subject` is not
     * meaningful and is stored as the filler `ALL_VULNS` with a null `subjectValue`
     * (both columns are NOT NULL).
     *
     * Suppresses **nothing**. Its sole effect is to remove the asset from the
     * EDR-coverage KPI denominator (`EdrCoverageKpiService`). It goes through the normal
     * request → approve/reject/cancel workflow, audit log, badge and expiry handling.
     */
    NO_EDR
}
