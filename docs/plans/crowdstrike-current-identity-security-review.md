# CrowdStrike identity and snapshot security review

Date: 2026-10-10. Scope: this implementation relative to the source baseline
captured before editing; unrelated existing working-tree changes were preserved.

| OWASP 2021 category | Result and evidence |
|---|---|
| A01 Access control | ADMIN is required for authoritative synchronization, reconciliation preview and retirement. Request asset IDs are checked through AssetFilterService before lookup; non-admin live lookup resolves an accessible existing asset and checks account consistency. A database result must belong to the requested asset; a scoped live result must match its bound AID or stable instance/account. Native current-row predicates do not replace caller authorization. |
| A02 Cryptography | Existing secrets and token handling reused. SHA-256 identity-lock keys fingerprint non-secret normalized machine identifiers; they are not password hashes. |
| A03 Injection | Query values remain bound parameters. Current-finding SQL composition uses Kotlin compile-time constants only, matching the repository's SQL-constant style. No request text becomes SQL structure. Existing hostname/FQL validation remains in use. |
| A04 Design | Complete pagination and selected-AID evidence precede replacement. Identity and asset locks serialize writers; observation versions reject stale responses. Severity coverage and unknown state prevent filtered absence from proving clean. Mixed-source duplicate inventory is preserved. |
| A05 Configuration | Additive migration; no new permissive network, CORS or authorization settings. |
| A06 Components | No dependency changes introduced. |
| A07 Authentication | Existing Micronaut authentication and roles reused; no new login/token mechanism. |
| A08 Integrity | Browser-submitted lists cannot establish deletion evidence. Synchronization refetches through the configured provider client. Missing total, 404 and partial pagination are failures. Legacy writes invalidate coverage claims. |
| A09 Audit | Existing actor-attributed import and enrollment history remain; snapshot records preserve selected AID, collection and completion time, coverage and retirement state. Derived refresh failure is recorded and retried. No secrets added to logs. |
| A10 SSRF | Existing configured CrowdStrike client/endpoints reused; synchronization accepts machine identifiers rather than arbitrary outbound URLs. |

No confirmed HIGH or CRITICAL issue was found in this implementation's reviewed
source paths. The static OWASP scanner has zero BLOCK findings. Its REVIEW output
also includes other pre-existing working-tree changes and is not itself proof of
full-repository security. Request-scoped findById calls have an immediately
preceding AssetFilterService check; bounded native queries retain role/caller
scopes. Legacy risk is explicitly retained until authoritative reconciliation.

Operational limits: this is snapshot consistency, not continuous live state.
Incomplete provider evidence cannot authorize retirement. A local reconciliation
preview cannot prove fleet-wide absence. Required database/browser verification
must be recorded separately from this source review.

Follow-up severity/readiness changes: A01 retains existing object and BETA role
checks; A02 exposes no credential; A03 adds only static severity predicates;
A04 distinguishes enabled from ready without bypassing provider requirements;
A05 keeps approved-provider configuration explicit; A06 adds no dependencies;
A07 preserves authentication; A08 retains complete provider snapshot evidence
and gates generation; A09 adds no secret-bearing logs; A10 preserves outbound
host and provider restrictions. The follow-up scanner reports zero BLOCK
findings. No HIGH or CRITICAL finding was identified in these changes.
