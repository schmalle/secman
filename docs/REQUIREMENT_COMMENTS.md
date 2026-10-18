# Requirement discussion comments

Users with only `REQ` can read and add plain-text comments independently of the
alignment-review workflow. Comments never submit votes, approve changes, change
requirement revisions, or modify release snapshots.

## Scope and permissions

| Scope | Target | Roles allowed to access discussion and create |
| --- | --- | --- |
| `REQUIREMENT` | Live requirement ID | ADMIN, REQ, SECCHAMPION |
| `RELEASE` | Release ID | ADMIN, REQ, REQADMIN, SECCHAMPION, RELEASE_MANAGER |
| `SNAPSHOT` | Immutable requirement snapshot ID | ADMIN, REQ, REQADMIN, SECCHAMPION, RELEASE_MANAGER |

Within those scope roles, each comment is visible only to its author and users with ADMIN or SECCHAMPION. REQADMIN and RELEASE_MANAGER do not grant access to other authors’ comments. Database queries filter by the authenticated author ID before pagination, including totals.

These entities use SecMan's existing global role-based target visibility. Asset and
workgroup grants do not apply. Each request checks the role for its scope and
resolves that exact target before querying or creating comments. Anonymous users
and other roles cannot access discussion, even where release metadata is public.

Live requirements accept comments while `isCurrent` is true. Comments store the
revision at submission; earlier comments remain in the requirement's thread with
their original revision labels after edits. A stale revision submitted by a client
returns 409 and requires a reload. The UI submits the displayed revision; writes
lock and refresh the requirement or release before checking revision/status so
concurrent content edits or archiving cannot bypass the check. Historical requirements are read-only.

Releases and their snapshots accept comments in PREPARATION, ALIGNMENT and ACTIVE.
ARCHIVED discussions remain readable but reject new comments with 409. New releases
start with separate empty discussions. Comments are not copied between scopes,
releases or snapshots, even if the underlying requirement ID is the same.

## User interface

From **Requirements → Requirements Overview**, expand a requirement to read or
add comments. Requirements without descriptive details can also be expanded.
Selecting a release exposes the snapshot discussion in each expanded requirement
and a separate whole-release discussion below the requirements table.

The **Releases** detail page shows whole-release discussion. Opening a snapshot
shows its own discussion in the detail modal. Each discussion identifies its
context, displays author, timestamp and revision where applicable, and pages
newest-first. Archived discussions show an explicit read-only message.

The form limits input to 2000 characters, rejects whitespace-only text, disables
submission while pending, preserves the draft on failure and allows retry.
Comments render as text, including strings resembling HTML or scripts.

## REST contract

`GET /api/requirement-comments/{scope}/{targetId}?page=0&size=20`

- `scope`: REQUIREMENT, RELEASE or SNAPSHOT (case-sensitive).
- `page`: zero-based, 0–1000000. `size`: 1–100; default 20.
- Response: `data` (always an array), `page`, `totalPages`, `totalItems`,
  `canComment`, `targetRevision` (nullable for whole releases).
- Each entry: `id`, `body`, `authorName`, `createdAt` (UTC instant),
  `requirementRevision` (nullable for whole releases), `canDelete`.
- Ordering: `createdAt DESC, id DESC`, paginated in the database.

`POST /api/requirement-comments/{scope}/{targetId}`

```json
{"body":"Please clarify the acceptance criteria.","expectedRevision":3}
```

- Returns 201 with the saved entry. `body` must be nonblank and at most 2000
  characters before trimming; stored text is trimmed.
- `expectedRevision` is required to match the current target revision for
  REQUIREMENT and SNAPSHOT; it is unnecessary for RELEASE.
- Author and creation time come exclusively from the authenticated server context.
  Extra client-supplied identity fields cannot set them.
- Missing targets return 404; role denials return 403 (401 without authentication);
  validation errors return 400; read-only or stale-revision writes return 409.
`DELETE /api/requirement-comments/{scope}/{targetId}/{commentId}`

- Authors can delete their own comments; ADMIN and SECCHAMPION can delete any comment.
- Returns 204 on success; hidden, missing, or wrong-target comments return 404.
- Deletion is permanent, audited without logging comment text, and allowed even on
  archived releases or historical requirements. It does not modify target content.
- The UI confirms deletion, preserves drafts, and refreshes the thread after success.
- No comment-edit endpoint is added.

MCP, CLI and the independent extension clients do not currently expose discussion
operations. This additive REST feature does not alter their existing requirement,
release or alignment contracts; no new commands or delegated permissions are added.

## Persistence and deletion

Migration `V292__requirement_discussion_comments.sql` creates `requirement_comment`.
A check constraint requires exactly one target foreign key. Indexed target/time/ID
queries do not load other threads or perform per-comment author lookups. The author
name is retained at submission. Deleting a user nulls the author ID and preserves
that attribution; deleting a target cascades its discussion deletion. No backfill,
feature toggle or new dependency is required.

## Verification

Focused service regressions:

```sh
./scripts/runbackendtests.sh --tests '*RequirementCommentServiceTest'
```

Full feature workflow in the isolated disposable stack:

```sh
./tests/e2e/run-e2e.sh requirement-comments.spec.ts --project=chrome
```

The browser test creates a user with exactly REQ, enters through the sidebar,
posts in all three scopes, verifies persistence and moderator visibility, retries a
failed write, verifies text rendering, exercises release and snapshot detail views,
and checks validation, pagination, author spoofing, USER/anonymous denials,
requirement revision changes, empty new-release threads and archive read-only state.
Privacy coverage checks a second REQ user, REQADMIN, RELEASE_MANAGER and SECCHAMPION across scopes, owner-only totals, unauthorized and wrong-target deletion, moderator deletion, and UI cancellation/deletion with persistence.
All fixture data belongs to the runner-owned schema, dropped on success or failure.
The normal application stack is preserved. These checks establish feature-level
integration evidence; the repository's combined finalizer gates remain required
before merge.
