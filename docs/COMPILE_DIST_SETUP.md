# `compiledistsetup.sh`

Build the secman backend, CLI, and frontend in one pass, then distribute the
resulting artifacts to two target hosts over SSH.

This script is intended for operators who have already provisioned two
machines (e.g. a primary and a standby app node, or two staging boxes) and
want a single command to "build everything locally, ship it to both."

It does **not** install systemd units, configure databases, open ports, or
manage environment files. It only delivers binaries / static assets. See
`docs/DEPLOYMENT.md` and `INSTALL.md` for runtime configuration on the
target.

---

## Synopsis

```
./scripts/compiledistsetup.sh <user1@host1> <user2@host2> [--dest <remote-dir>]
./scripts/compiledistsetup.sh -h | --help
```

## Arguments

| Argument            | Required | Description                                                                                  |
|---------------------|----------|----------------------------------------------------------------------------------------------|
| `<user1@host1>`     | yes      | First deployment target in `user@host` form. `host` may be a hostname or IP. Port is the SSH default (22). |
| `<user2@host2>`     | yes      | Second deployment target, same form. The two targets are independent — failure of one does not roll back the other. |
| `--dest <remote-dir>` | no     | Remote destination directory on **both** targets. Default: `secman` (relative to the SSH user's home). Absolute paths are honoured (e.g. `/opt/secman`). |
| `-h`, `--help`      | no       | Print usage and exit.                                                                        |

Exactly **two** targets must be supplied. Any other count is rejected.

Targets must contain an `@` — bare hostnames are rejected to force the
operator to be explicit about the SSH user.

## Examples

```bash
# Two FQDN targets, default remote layout under ~/secman on each host.
./scripts/compiledistsetup.sh \
    deploy@app01.example.com \
    deploy@app02.example.com

# Different SSH users on each box, custom absolute remote directory.
./scripts/compiledistsetup.sh \
    alice@10.0.0.10 \
    bob@10.0.0.11 \
    --dest /opt/secman

# Use --dest with a path under the remote home.
./scripts/compiledistsetup.sh \
    ops@stage-a \
    ops@stage-b \
    --dest releases/2026-05-17
```

## Prerequisites

On the **build host** (where you run the script):

- `bash` 4+, `ssh`, `rsync`, `npm` available on `PATH`.
- A working JDK 21 toolchain (used via `./gradlew`).
- The Gradle wrapper (`./gradlew`) executable in the repo root.
- Internet access for Gradle and npm dependency resolution (unless caches
  are pre-warmed).

On **each target**:

- A reachable SSH service on port 22.
- The SSH user's public key from the build host present in
  `~<user>/.ssh/authorized_keys`. The script uses `BatchMode=yes`, so it
  will fail immediately if a password / passphrase prompt is needed
  instead of hanging.
- `rsync` installed (used as the remote receiver).
- Write permission to `<remote-dir>` (or to its parent, so it can be
  created).
- Enough free disk for the artifacts (~250 MB headroom is comfortable).

The script does not require any secman-specific service to be running on
the target — it only delivers files.

## What gets built

| Component | Gradle / npm command                       | Output                                                  |
|-----------|--------------------------------------------|---------------------------------------------------------|
| Backend   | `./gradlew :backendng:clean :backendng:shadowJar -x test` | `src/backendng/build/libs/backendng-<ver>-all.jar`     |
| CLI       | `./gradlew :cli:clean :cli:shadowJar -x test`             | `src/cli/build/libs/cli-<ver>-all.jar`                 |
| Frontend  | `npm ci && npm run build` (in `src/frontend`)             | `src/frontend/dist/` (static assets)                   |

Tests are skipped (`-x test`) so the script runs at deploy speed. Run
`./gradlew build` separately if you want the full test suite first.

The artifact JARs are located via a glob (`backendng-*-all.jar`,
`cli-*-all.jar`), so version bumps in `build.gradle.kts` do not require
script changes.

## What gets shipped

On each target, the script creates / refreshes this layout under
`<remote-dir>`:

```
<remote-dir>/
└── src/
    ├── backendng/
    │   └── build/libs/backendng-<version>-all.jar
    ├── cli/
    │   └── build/libs/cli-<version>-all.jar
    └── frontend/
        └── dist/
            ├── client/
            ├── server/
            └── (entire contents of src/frontend/dist/)
```

Transfer is done with `rsync -az --delete`:

- `-a` preserves permissions / timestamps.
- `-z` compresses over the wire.
- `--delete` removes files from the target frontend `dist/` tree that no
  longer exist locally. Backend and CLI jars are copied into their Gradle
  `build/libs` locations so existing runtime scripts keep working.

## Behaviour and order of operations

1. Parse arguments; validate that exactly two `user@host` targets were given.
2. Resolve the repository root from the script's own location (so the
   script works regardless of the caller's current directory).
3. Verify required tools (`ssh`, `rsync`, `npm`, `./gradlew`) exist.
4. Build backend, then CLI, then frontend, in that order.
5. Resolve the resulting artifact paths; fail fast if anything is missing.
6. For each target, in order:
   - `ssh` with `mkdir -p` to create `backend/`, `cli/`, `frontend/`
     subdirectories under `<remote-dir>`.
   - `rsync` the backend JAR.
   - `rsync` the CLI JAR.
   - `rsync` the frontend `dist/` tree.
7. Print a final "All targets updated." line.

Targets are processed **sequentially**, not in parallel. The first target
must complete (successfully or not) before the second begins. If the first
target fails, the script exits and the second is never touched.

## SSH options used

```
-o BatchMode=yes
-o StrictHostKeyChecking=accept-new
-o ConnectTimeout=15
```

- `BatchMode=yes` — never prompt for a password / passphrase. Misconfigured
  keys produce an immediate failure instead of a hang.
- `StrictHostKeyChecking=accept-new` — on the first connection, the host
  key is pinned to `~/.ssh/known_hosts` without prompting. Subsequent
  connections require it to match. If you prefer to vet host keys
  manually, SSH to each target once before running the script so the
  fingerprint is already pinned.
- `ConnectTimeout=15` — fail unreachable hosts in 15 s rather than waiting
  for the system default TCP timeout.

Non-standard SSH ports, jump hosts, alternative identity files, etc. are
**not** supported via flags. Configure them in `~/.ssh/config` instead,
e.g.:

```
Host app01.example.com
    Port 2222
    IdentityFile ~/.ssh/id_secman_deploy
    User deploy
```

…then call the script with `deploy@app01.example.com`.

## Exit codes

| Code | Meaning                                                                |
|------|------------------------------------------------------------------------|
| 0    | All builds succeeded and both targets received all three artifact sets. |
| 1    | Argument validation failed, a required tool was missing, a build step failed, an expected artifact was missing, or an `ssh`/`rsync` invocation failed. |

There is no partial-success exit code. If the script returns non-zero, do
not assume the deployment is consistent across the two hosts — inspect
the output and re-run.

## Idempotence and re-runs

Re-running the script with the same arguments is safe and reproducible:

- The Gradle `clean` tasks force a fresh shadowJar build.
- `npm ci` reproduces `node_modules` from the lockfile (it deletes
  `node_modules/` first), so a stale dependency cannot leak in.
- `rsync --delete` ensures the remote subdirectories reflect the local
  build exactly — no orphaned files left over from previous releases.

There is no built-in roll-back. If you need one, deploy to a versioned
`--dest` and flip a symlink out-of-band.

## Security notes

- The script never reads, prints, or forwards secrets. Runtime
  configuration (DB credentials, JWT secret, Falcon keys, etc.) is the
  operator's responsibility on the target, via the patterns described in
  `docs/PASS_CLI.md` and `docs/ENVIRONMENT.md`.
- Tests are skipped during build. If you are deploying to anything
  resembling production, run `./gradlew build` (full test suite) and the
  `/e2ejs` + `/e2evulnexception` E2E gates from CLAUDE.md **before**
  invoking this script.
- `StrictHostKeyChecking=accept-new` is a convenience trade-off. For
  high-trust environments, pre-populate `~/.ssh/known_hosts` and remove
  the option (or override with an `~/.ssh/config` entry that sets
  `StrictHostKeyChecking yes`).
- `BatchMode=yes` means a forgotten / unloaded SSH agent or a wrong
  passphrase produces an immediate error rather than a silent hang —
  treat the error and re-run rather than working around it.

## Troubleshooting

**`Error: required tool 'rsync' not found in PATH`**
Install `rsync` on the build host (and confirm it is present on the
targets — `rsync` is needed on both ends).

**`Permission denied (publickey)` from `ssh`**
The build host's key is not in the target user's `authorized_keys`, or
the wrong key is being offered. Confirm with:

```bash
ssh -o BatchMode=yes deploy@app01.example.com 'echo ok'
```

If that fails, fix the key setup before re-running the script.

**`Host key verification failed`**
The target's host key changed since it was first pinned (or a man-in-the-
middle is at play). Verify out-of-band, then remove the stale entry from
`~/.ssh/known_hosts` and re-run.

**Build succeeds but `backend shadowJar not found under …`**
Check the actual file in `src/backendng/build/libs/`. If the JAR name no
longer matches `backendng-*-all.jar` (e.g. a Gradle config change removed
the `-all` classifier), update the glob in the script.

**`rsync: command not found` on the remote**
Install `rsync` on the target. `scp` is not used as a fallback.

**Stale artifacts left under `<remote-dir>`**
`rsync --delete` only prunes within each subdirectory it copies into
(`backend/`, `cli/`, `frontend/`). Files placed at the top level of
`<remote-dir>` or in unrelated subdirectories are untouched. Clean those
up manually if needed.

## Related

- `scripts/compile.sh` — minimal backend + frontend build, no deploy.
- `scripts/compilecli.sh` — minimal CLI build, no deploy.
- `scripts/startbackenddev.sh` — local dev start (not for production targets).
- `docs/DEPLOYMENT.md` — runtime configuration on a deployed host.
- `docs/PASS_CLI.md` — secret resolution for the runtime environment.
