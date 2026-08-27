# Duplication that must stay

> **Sync policy (two-way, mandatory)**: counterpart is
> `.claude/skills/optimizer/references/deliberate-duplication.md`. Edit both in
> the same commit; verify with `./scripts/check-skill-sync.sh`.

`DUP-BLOCK` reports that code appears twice. Sometimes twice is correct. Read
this before extracting anything in the areas below — each entry is a case where
merging the copies would remove a property the repo depends on.

## Fail-closed redundancy

**`McpToolPermissions.LISTING` and `.CALLING`** are two maps that largely repeat
each other, and that is the design. A tool missing from `CALLING` fails closed —
it is listed but not callable, which looks like a bug and is safe. A single
merged map cannot express that, and a missing guard fails *open*, which looks
like nothing at all.

The residual risk — the two maps silently diverging — is real and wants a test
asserting expected membership. It does not want a merge.

The same logic covers RBAC checks that exist in both a controller `@Secured`
annotation and the UI. They are not duplicates: the controller is the boundary,
the UI is UX. Removing either "copy" removes a different thing.

## Zero-dependency contracts

**`src/relay` and `src/clinotify`** have no third-party dependencies by
contract. The relay hand-rolls ACME, JWS, JWT and its own rate limiter; some of
that code resembles other code in the same module, and some of it resembles a
library you could import.

Both observations lead nowhere. The zero-dependency rule is a stated
supply-chain decision for a DMZ-facing component, and replacing hand-rolled
protocol code with a library would reduce the line count and increase the risk.
Within the module, extract only when the copies genuinely share a reason to
change — and never by adding a dependency to do it.

## Structural guarantees that look like waste

**`store.Section`'s defensive copy** duplicates every payload on every read.
The copy exists so a handler can never mutate stored state, and that guarantee
is structural rather than conventional. The mechanism may change — a versioned
immutable snapshot with pointer-swap on `Put` gives the same guarantee without
the per-request copy — but the guarantee itself is not negotiable, and a
replacement that relies on callers behaving is not a replacement.

**`ExportJobService`'s REQUIRES_NEW per progress tick** repeats a transaction
boundary that could obviously be hoisted. Each tick committing independently is
what makes job progress observable while the job runs and survives a crash
mid-export.

## Deliberate divergence between near-twins

**The `-aws` script variants.** Roughly a dozen shell scripts have an `-aws`
fork. The *mode* is real — the two secret sets genuinely differ — and deleting
the AWS path would break deployment. The finding is the forking, not the mode:
the right fix is one script with a flag, not one script.

**Test setup blocks.** Integration tests repeat seeding sequences, and that
repetition is often what makes a failing test readable on its own. Extract a
`TestDataFactory` builder when the sequence is genuinely shared and stable —
that helper already exists and should be used. Do not extract a fixture whose
only purpose is to make two tests look alike; a test that requires reading
three other files to understand has been optimized in the wrong direction.

**E2E script verbosity.** The phase-by-phase assertions in the E2E drivers are
the value, not the cost. Consolidate process spawns and shared helpers; leave
the explicit assertions alone.

## The question that settles most cases

*If this changes, must the other change in the same commit?*

Yes → one concept, extract it.
No → two things that rhyme, leave them and record the decision.
"I do not know" → they have not diverged yet; note it and look again next time.
