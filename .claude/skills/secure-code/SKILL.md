---
name: secure-code
description: >
  Generate and verify secman code that satisfies OWASP Top 10:2021 by
  construction. Routes the change to the A01-A10 categories it can actually
  violate, names the existing repo control to reuse for each one, then verifies
  with `./scripts/owasp-check.sh` (deterministic static gate), a semantic
  re-read of the diff for the rules no grep can see, and `/security-review` on
  auth, crypto, upload, export or outbound-HTTP diffs. Ends with the one-line
  OWASP verdict CLAUDE.md's review gate requires. Use this skill **before
  writing** any backend endpoint, repository query, MCP tool, file upload,
  export, HTML sink or outbound HTTP call, and whenever the user says "secure
  code", "write this securely", "generate secure code", "owasp", "security
  check", "is this secure", "harden this", "security review before I commit",
  or asks for code in a security-sensitive area. Not a substitute for
  `/security-review` — it invokes it.
---

> **Sync policy (two-way, mandatory)**: This file and
> `.agents/skills/secure-code/SKILL.md` are one skill kept in two harness trees
> — Claude Code reads this copy, Codex reads the other. Whichever copy an
> agent edits, the same change is ported to the other **in the same commit**;
> translate harness-specific mechanics rather than copying verbatim (e.g. Bash
> tool `dangerouslyDisableSandbox: true` ↔ `sandbox_permissions:
> "require_escalated"`). `.claude/skills/` is the tie-breaker when the two
> disagree — that is a conflict rule, not a licence to edit one side only.
> Verify with `./scripts/check-skill-sync.sh` (exit 0) before calling the
> change done. See `CLAUDE.md` §"Tooling Conventions" and `AGENTS.md` §Skills.

# Secure code generation — OWASP Top 10:2021 by construction

This skill is for the agent **doing the writing**, and it is deliberately not
delegated to a separate reviewer that runs afterwards. Its job is to shape code
before it exists; handing it to something downstream would put the guidance
where the writing is not happening.

## The problem this solves

`CLAUDE.md` §"OWASP Top 10 Compliance" is already binding policy, and it is
already good. It is also ~60 bullet points spanning ten categories, which is
more than anyone holds in working memory while writing a controller. The
predictable failure is not defiance, it is **partial recall**: you remember
`@Secured` and forget that the id in the path is untrusted; you remember to
bind `:name` and forget `ExcelSanitizer` on the export three files over.

So this skill does three things policy prose cannot:

1. **Narrows ten categories to the three that your change can actually
   violate**, before you write anything.
2. **Names the one existing function to call** for each — because the second
   implementation of a control is how the first one gets bypassed.
3. **Verifies mechanically**, so "I followed the rules" becomes a red or green
   exit code instead of a claim.

Security-first is Hard Principle 1 and the OWASP checklist is binding, not
advisory. This skill is how that gets operationalized; it does not add new
policy, and where this file and `CLAUDE.md` ever disagree, `CLAUDE.md` wins.

---

## Phase 1 — Route the change (before writing code)

Answer these about what you are about to build. Every **yes** pulls in a row.
Do not read all ten categories; read the rows you matched, then their entries
in `references/controls.md`.

| If the change… | Categories | The control you must reuse |
|---|---|---|
| adds or edits an HTTP endpoint | A01, A04 | `@Secured` + narrowest role; deny by default |
| accepts an **id** from the client | **A01** | `AssetFilterService.canAccessAsset` / `getAccessibleAssetIds` |
| adds or edits an MCP tool | **A01** | `McpToolPermissions.LISTING` **and** `.CALLING` + an `McpToolGuards` check |
| writes a query (JPQL, native, criteria) | A03, A01 | bound `:params`; allowlist enum for column/sort/table |
| returns a list | A04 | page at the query — never `findAll()` then slice |
| renders user-supplied HTML | A03 | `DOMPurify.sanitize` **at the assignment site** |
| writes an Excel/CSV cell | A03 | `ExcelSanitizer.sanitize` on every user-controlled cell |
| accepts a file upload | A08 | `ImportController.validateFile` shape: size, extension, content type, non-empty — **before parsing** |
| parses XML | A08 | the four `setFeature` calls from `NmapParserService` |
| reads an archive | A08 | reject `..` entries, bound the decompressed size |
| makes an outbound HTTP call | **A10** | `https` + host allowlist; reject loopback/link-local/RFC-1918/`169.254.169.254`; never follow redirects out of policy |
| touches passwords, keys or tokens | A02, A07 | `BCryptPasswordEncoder`; JWT stays in the HttpOnly `secman_auth` cookie |
| stores a credential | A02 | `EncryptedStringConverter`, never returned, `***HIDDEN***` means "keep" |
| adds config, CORS or headers | A05 | explicit origin allowlist; never weaken `SecurityHeadersFilter` |
| adds a dependency | A06 | prefer the classpath; pin exact; `src/clinotify` is stdlib-only |
| logs anything | A09 | actor + target + outcome; never the secret's **value**; strip CR/LF |
| catches an exception | A09 | never an empty block |
| returns an error to a client | A05 | generic message out, detail to the server log |

**Say out loud which rows you matched.** A change that matches no row is
almost always a change you have not finished describing to yourself.

## Phase 2 — Write it with the existing control

Read `references/controls.md` for the matched rows: it holds the canonical
snippet and the file that already does it correctly, so the new code can copy a
control that is already reviewed rather than invent a parallel one.

Three rules govern the writing itself:

- **Reuse, never re-implement.** If a control exists, call it. A second
  sanitizer, second URL validator, second permission check is a divergence that
  will be patched on one side only.
- **Never work around a control to make something build, start or pass.**
  `JwtSigningValidator`, `DatabaseCredentialValidator` and
  `DatasourceUrlValidator` fail the boot on weak config *by design*. A test that
  needs `HttpOnly: false`, a feature that needs `unsafe-eval`, an import that
  needs the XXE block relaxed — change the test, the feature, the import.
- **Deny by default.** Start from the narrowest role that works and widen
  deliberately. `IS_AUTHENTICATED` with a TODO is not a decision.

## Phase 3 — Verify

Three checks, in this order. None replaces another: the first is mechanical and
blind, the second is semantic and unautomatable, the third is adversarial.

### 3a. Static gate (always)

```bash
./scripts/owasp-check.sh            # this change vs origin/main, includes untracked files
./scripts/owasp-check.sh --verbose  # same, with the offending line text
```

Findings come in two severities:

- **BLOCK** — introduced by *this* change. Fix it. Exit code 1. A BLOCK finding
  means the change is not complete, in the same sense a failing build is not
  complete.
- **REVIEW** — a pattern worth a decision, often pre-existing in a file you
  touched. Look at each one and **state what you decided**. "Reviewed, the id
  is workgroup-scoped by the caller" is an answer; silence is not.

Two things it will not do, by design: it does not scan the whole repo unless
asked (`--all`, for audits — the repo has substantial pre-existing findings and
a gate that is red on arrival is a gate people learn to ignore), and it does not
edit anything.

If you believe a BLOCK finding is a false positive, do not silence it locally —
say so explicitly in your report and, if the pattern is genuinely approved,
tighten the rule in `scripts/owasp-check.sh` **and** add the approved pattern to
`scripts/test/owasp-check-test.sh` as an `expect_silent` case, so the exemption
is tested rather than remembered.

### 3b. Semantic re-read (always)

The scanner sees text. Roughly half of the OWASP rules here are about meaning
and no grep will ever catch them. Read `references/blind-spots.md` and walk your
diff against it — that file is the checklist for exactly the failures a green
scanner cannot rule out, chiefly:

- Is that id **actually** owner-scoped, or does the query just happen to filter?
- Is the role the **narrowest** one that works, or the first one that compiled?
- Is the business invariant enforced **server-side**, or only in the UI?
- Does the error message distinguish "no such account" from "wrong password"?
- Is the new outbound host reachable at an internal address?

### 3c. `/security-review` (conditionally, and it blocks)

Run it on the branch diff when the change touches **authentication,
authorization, crypto, file upload, export, or any outbound HTTP call** —
`CLAUDE.md`'s review gate requires this, and `/finalizer` runs a HIGH/CRITICAL
pass at the end anyway.

**A finding at HIGH or above blocks the change.** Fix it. Noting it is not
closing it.

### 3d. Report the verdict

Close with the single line `CLAUDE.md`'s review gate asks for, naming only the
categories the change actually touched:

```
OWASP: A01/A03/A09 touched — clean
```

If something is unresolved, say so in the same line rather than omitting it:

```
OWASP: A01/A10 touched — A10 outbound host allowlist deferred, see note below
```

---

## What this skill is not

- **Not a replacement for `/security-review`** — step 3c invokes it.
- **Not a replacement for the mandatory E2E gates.** `/e2ejs` and
  `/e2evulnexception` are separate, still required, and still need a running
  stack (`CLAUDE.md` Hard Principle 7).
- **Not a completion gate on its own.** A change is complete when
  `./gradlew build` is clean **and** `./scripts/startbackenddev.sh` starts
  cleanly (Principle 5), frontend edits pass `npm ci && npm run build`
  (Principle 5a), and `extensions/` clients are checked for contract drift
  (Principle 5b). Secure and broken is still broken.
- **Not offline-only.** Phases 1–3b need no stack, no `pass-cli`, no network.
  Phase 3c and the build/startup gates do.

## Maintaining the gate

`scripts/owasp-check.sh` is only as good as its last regex. Two failure modes,
and only one of them is visible:

- A **false positive** is loud — someone complains and it gets fixed.
- A **false negative** is silent. The rule quietly stops matching, the gate
  reports OK forever, and nobody learns.

`scripts/test/owasp-check-test.sh` exists for the second one: it builds a
throwaway git repo, plants a deliberately vulnerable fixture per rule, asserts
the rule fires **at the right severity**, then plants the repo's *approved*
pattern for the same risk and asserts the rule is silent. Run it after any edit
to either script:

```bash
./scripts/test/owasp-check-test.sh --verbose
```

Adding a rule means adding both fixtures. A rule with no test is a rule that
will rot into a green light.

Two files are exempt from scanning, and only two: `scripts/owasp-check.sh`
(it contains the patterns it hunts for) and `scripts/test/owasp-check-test.sh`
(it is *made of* vulnerable fixtures). Both would otherwise be permanently red
for doing their job, and a gate that is red for the wrong reason is a gate people
stop reading. The exemption is not "tests are exempt" — `CLAUDE.md` forbids a
literal credential in "source, tests, scripts or fixtures", every other test file
stays in scope, and the self-test asserts exactly that. The exempt paths are
printed on every run so the blind spot is visible rather than assumed.
