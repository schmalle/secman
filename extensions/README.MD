# secman extensions

Independent client repositories that build on the secman backend. Each one has
its own git remote, its own review process and its own release cycle. They are
cloned into this directory for convenience and are **gitignored by the secman
repo** — `git status` at the repo root never shows them, and no build or test
in this repository compiles or exercises them.

| Folder | Repository | What it is |
|---|---|---|
| `secman_ai_github/` | https://github.com/schmalle/secman_ai_github | GitHub security scanner. Pushes findings into secman. |
| `secman_visual_check/` | https://github.com/schmalle/secman_visual_check | External attack-surface scanner. Pushes findings into secman. |
| `secman_app_ios/` | https://github.com/schmalle/secman_app_ios | iOS/iPadOS status app. Talks **only** to the relay, never to secman. |

## Two different kinds of client

The distinction matters for security review, so it is worth stating plainly.

**Backend clients** (`secman_ai_github`, `secman_visual_check`) authenticate to
secman itself and **write** data — vulnerabilities, assets, findings. They hold
secman credentials and reach `/api/…` and `/mcp` directly. Changing a backend
endpoint can break them silently, which is why `CLAUDE.md` §Extension Clients
requires re-verifying their five contract dimensions (path, method, request
field names, response fields read, `@Secured` roles / required headers) whenever
a touched endpoint is one they call.

**Relay clients** (`secman_app_ios`) never touch secman at all. They talk to the
relay described in [`docs/RELAY.md`](../docs/RELAY.md), which holds a read-only
snapshot that secman pushes out to it. The app has no secman credential, no
secman URL and no write path. Consequently a change to a secman `/api/…`
endpoint cannot break the iOS app — but a change to the **relay snapshot
contract** (`src/backendng/…/com/secman/relay/RelayDtos.kt`, the section names
in `RelaySnapshotBuilder`, or the relay's own API under `src/relay/`) can, and
that contract is versioned with `schemaVersion` for exactly that reason.

## Rediscovering the backend surface

Never trust a written list of endpoints — a newly added call gets checked by
nobody. Regenerate it:

```bash
grep -rnE '/api/|"/mcp"|X-MCP-User-Email' extensions --include='*.py' --exclude-dir=.venv
```

For the iOS client, the equivalent sweep is against the relay API:

```bash
grep -rnE '/api/v1/|/ingest/v1/' extensions/secman_app_ios --include='*.swift'
```

## Working in an extension repo

- Commit **inside** the extension repo and **path-scoped**
  (`git -C extensions/<repo> commit -m "…" -- <files>`) so pre-existing dirty
  files are not swallowed. Never `git add -A` / `commit -a`.
- These repos have their own review. Push to a branch and open a PR; do not
  push to their default branch.
- An `@Secured` tightening in secman is a **report**, not a fix: you cannot see
  which roles a client's service account holds.
