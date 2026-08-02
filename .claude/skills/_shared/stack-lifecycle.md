# Shared stack lifecycle contract

Every secman skill that touches the running backend or frontend obeys this
contract. It exists because the rules used to be restated in each skill, and
restatements disagreed — one skill started the backend without stopping it
first, another exported the exact `localhost` literal CLAUDE.md forbids. One
copy cannot drift from itself.

**This file is not optional reading.** A skill that cites it expects you to have
read it before touching the stack.

**Boundary rule:** if changing a fact would require editing more than one skill,
it belongs here. What the skill *tests*, its script invocation, its env vars, its
error-classification table and its report template stay in the skill.

`_shared/` holds no `SKILL.md` and is not itself a skill.

---

## 1. Cold start is mandatory and unconditional

Never reuse a running backend or frontend. A running instance may predate the
current working tree, which makes a green run meaningless — you would be testing
yesterday's code. Always run both stop scripts first, **even when the ports look
free**; they are safe no-ops when nothing is running.

```bash
./scripts/stopbackenddev.sh
./scripts/stopfrontenddev.sh
```

Never `kill` or `lsof | xargs kill` inline — the scripts know which processes are
theirs.

Wait ~3 seconds, then confirm both ports are actually free before starting:

```bash
lsof -iTCP:8080 -sTCP:LISTEN -n -P
lsof -iTCP:4321 -sTCP:LISTEN -n -P
```

That confirmation is load-bearing, not ceremony — see §4.

## 2. Starting services

Always start via the wrapper scripts. Never call `./gradlew run` or `npm run dev`
directly (CLAUDE.md, Tooling Conventions) — the wrappers source `pass-cli` for
secrets and configure the JVM.

```bash
./scripts/startbackenddev.sh     # port 8080
./scripts/startfrontenddev.sh    # port 4321
```

Start each in the background via `nohup … &`, redirecting stdout and stderr to
standardized log paths so later phases can find them, and record the PIDs for
teardown:

- `.e2e-logs/backend.log`
- `.e2e-logs/frontend.log`

`.e2e-logs/` is gitignored.

**Both start scripts must run outside the sandbox.** They reach `pass-cli` for
secrets, which a sandboxed shell cannot do, so a sandboxed start fails in a way
that looks like a code error. The mechanic differs per harness:

| Harness | Mechanic |
|---|---|
| Claude Code | Bash tool `dangerouslyDisableSandbox: true` |
| Codex | `sandbox_permissions: "require_escalated"` |

## 3. Liveness is port-bind, not HTTP

Per CLAUDE.md's E2E Runner section. An HTTP probe can report a backend "down"
while Micronaut is still wiring beans, and can report it "up" against a stale
process.

```bash
lsof -iTCP:8080 -sTCP:LISTEN -n -P    # backend, 120s budget
lsof -iTCP:4321 -sTCP:LISTEN -n -P    # frontend, 60s budget
```

## 4. Two failure modes that look like something else

Both of these have wasted fix-loop iterations on code that was never broken.

**The stop scripts always exit 0 — even when the kill failed.** They use `set -u`
without `-e`, and their `if` block returns 0 when no process matched. A genuine
"I tried to kill it and it would not die" is indistinguishable from "nothing was
running" by exit code alone. This is why §1 requires re-polling the port after
stopping rather than trusting the exit status.

**The frontend may bind 4322 instead of 4321.** `startfrontenddev.sh` does not
check whether the port is already taken, and Vite auto-increments on a bind
conflict rather than failing. A stale process on 4321 therefore produces a
frontend that started successfully while `lsof -iTCP:4321` times out. Before
concluding the frontend failed to start, check:

```bash
lsof -iTCP:4322 -sTCP:LISTEN -n -P
grep -iE 'port 432[0-9]|Local:' .e2e-logs/frontend.log | tail -3
```

If it bound 4322, the fix is to stop everything and cold-start again — not to
edit frontend code.

**Backend port never binds** is not automatically a code bug either.
`startbackenddev.sh` runs a full `clean` build every invocation, so a slow
machine can exceed the 120s budget while still compiling. Check whether the
gradle process is alive and the log still growing before concluding it crashed;
only then read `.e2e-logs/backend.log` for the compile or runtime error.

## 5. Host URLs and credentials

`SECMAN_HOST` comes from `pass-cli` and is the only correct target for functional
checks. **Never hardcode `http://localhost:8080` or `http://localhost:4321`**, and
never `curl localhost` — CLAUDE.md Hard Principle 6.

The `lsof` port checks above are the sole exception, and they are not HTTP: they
ask the kernel whether a process bound a local port, which is exactly the
question, and they carry no URL.

Credentials come from `pass-cli` only. Note the env-var name and the vault field
name are not always the same — the username env var is `SECMAN_USER_USER` but the
vault field is `SECMAN_USER_NAME` (`pass://Test/SECMAN/SECMAN_USER_NAME`). Getting
this wrong surfaces as a login failure, which reads like an application bug.

If a normal-user login fails because the account does not exist on this instance,
`./scripts/test/provision-test-user.sh` creates it. It is idempotent and exits 0
when the account is already there.

## 6. Fix-loop budget

Skills that iterate on failures get **5 iterations total**, counted across the
whole run — not 5 per phase and not 5 per failure class. A nested loop with its
own budget multiplies rather than adds, which is not what a maximum means.

Within that budget:

- Apply fixes with services **stopped**, then cold-start and re-run. Never edit
  code while services are running; the next run must reflect the edit.
- Track which errors you have already attempted. If the same error survives two
  attempts, stop working on it and report it — a third identical attempt is
  rarely different, and it consumes budget other failures need.
- When the budget is exhausted, **stop and report what remains**. Do not silently
  continue, and do not report success. An honest "3 of 5 fixed, here are the 2
  that survived and what I tried" is a useful result; a partial run described as
  a pass is not.

## 7. Teardown

Stop both services when finished — **on the failure path too**, not only on
success. A skill that leaves the stack up on failure makes the next run's
mandatory cold-start do the cleanup, which hides the fact that the previous run
died.

```bash
./scripts/stopbackenddev.sh
./scripts/stopfrontenddev.sh
```

## 8. Reporting

Two rules, because both failure modes are silent:

- **Distinguish "checked and clean" from "not checked."** Silence reads as a
  pass, so a step you skipped looks identical to a step that found nothing. If
  you could not complete a step, name it and say why.
- **Every claim needs a locator** — `file:line` for findings, a log path for
  errors, a count for anything quantitative. A report the reader cannot verify
  quickly is a report they will stop trusting.
