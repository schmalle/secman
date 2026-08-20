# Shielded development container (Apple `container`, macOS on Apple silicon)

A development environment that runs the coding agents — Claude Code and Kimi CLI —
and the whole Secman stack inside one Apple `container` VM, so that:

- the container sees **exactly one host path**, the source tree you name;
- outbound traffic reaches **only** an explicit allowlist, enforced by iptables — no proxy;
- ports **8080**, **4321**, **443** and **3306** come back to the Mac;
- `pass-cli` works inside, so the canonical secret path (`docs/PASS_CLI.md`) is unchanged.

It is a shield, not a jail: it protects your Mac and your network from an agent
that takes a wrong turn. It is not a defence against a determined adversary who
already controls a process inside the container — see [What this does and does
not protect](#what-this-does-and-does-not-protect).

---

## Quick start

```bash
# 1. one-off: install Apple's container CLI (macOS 26 on Apple silicon)
#    https://github.com/apple/container/releases

# 2. build the dev image (~10 min the first time)
./scripts/container/secman-container.sh build

# 3. start the shield, sharing exactly this repository
./scripts/container/secman-container.sh up --src "$PWD"

# 4. log in to Proton Pass once — the session lives in a named volume and survives restarts
./scripts/container/secman-container.sh shell
dev@secman-box:/workspace$ pass-cli login --interactive

# 5. drive it
./scripts/container/secman-container.sh claude          # Claude Code, inside the shield
./scripts/container/secman-container.sh kimi            # Kimi CLI, inside the shield
./scripts/container/secman-container.sh shell           # or a plain shell
```

Inside the container the repo's own scripts work unchanged:

```bash
cd /workspace
./scripts/startbackenddev.sh      # :8080
./scripts/startfrontenddev.sh     # :4321
```

Reach them from the Mac at `http://localhost:8080`, `http://localhost:4321`,
`https://localhost/` (the TLS front door) and `localhost:3306`.

---

## What is inside

| | |
|---|---|
| OS | Debian 12 (bookworm), arm64 |
| JVM | Eclipse Temurin **25** (`JAVA_HOME=/opt/java`), Gradle **9.7.0** on `PATH` |
| Node | current **24.x**, checksum-verified against `nodejs.org` |
| Go | current **1.24.x** — `src/relay` is a separate module `./gradlew` never builds |
| Python | via `uv`, 3.13 for Kimi CLI |
| DB | MariaDB **11.4** server + client |
| Agents | Claude Code (`claude`), Kimi CLI (`kimi`) |
| Secrets | `pass-cli` (Proton Pass) |
| Shield | `iptables` (+ `ipset` where the kernel has it) — no proxy |
| TLS | `nginx` on :443 fronting :4321 and :8080 |

Everything runs as the unprivileged user `dev`. There is no `sudo`: the firewall
and its allowlist are root-owned, so an agent session cannot dismantle the shield
it is running in. When you genuinely need root,
`./scripts/container/secman-container.sh root` gets you there from the Mac.

---

## Filesystem exposure

One bind mount, and it is the one you pass:

```
--volume "$SRC:/workspace"
```

Everything else that has to persist — the Gradle and npm caches, the agent
configuration, the Proton Pass session, the MariaDB data directory — lives in
**named volumes**, which are sparse disk images inside `container`'s own store.
They survive restarts, they are invisible to the Mac's filesystem, and they do
not widen the exposure the container exists to narrow. They are also
considerably faster than a shared directory: named volumes are ext4 over
virtio-blk, while a bind mount goes through virtiofs.

`up` refuses over-broad source paths (`/`, `/Users`, `$HOME`, `/System`, …) rather
than trusting the caller to notice. It warns if the path is not a git working
tree.

To throw everything away, volumes included:

```bash
./scripts/container/secman-container.sh destroy
```

---

## Egress control

**iptables is the whole enforcement layer. There is no proxy**, so nothing in the
container needs `HTTP_PROXY`, no JVM needs `-Dhttps.proxyHost`, and no tool can
behave differently by ignoring those variables. It also means one thing has to be
said plainly rather than buried:

> **Filtering is by address, not by name.** The kernel sees packets, not
> hostnames. Two consequences follow, and neither has a fix at this layer:
> anything else answering on an allowlisted address is also reachable (CDNs host
> many sites per address), and an address that rotates out of DNS stays permitted
> until the next refresh while its replacement is not.
>
> The second is handled — see [Refreshing](#refreshing). The first is inherent:
> if you need "this name and nothing else that shares its address", only a
> name-aware gate can give you that, and this container does not run one.

### How the allow-set is built

At start-up, and on every refresh:

1. every **hostname** in the allowlist is resolved to its A records;
2. every **address or CIDR literal** in the allowlist is taken verbatim;
3. **GitHub's published ranges** are fetched from `https://api.github.com/meta`.
   GitHub is spread across dozens of prefixes — `github.com`, `codeload`,
   `objects.githubusercontent.com`, releases, packages — and resolving a few
   names covers a fraction of it. `SECMAN_EGRESS_GITHUB_META=0` turns this off.

The result goes into an **ipset** where the kernel supports it (one hash lookup
per packet, and refreshes are an atomic swap), or into a chain of plain rules
where it does not. `egress-check` tells you which.

### The ruleset

`OUTPUT` policy is `DROP`. The only ways out are:

- loopback;
- replies on connections the container already established;
- DNS to the resolvers in `/etc/resolv.conf` — and resolution alone reaches
  nothing, because the address it returns still has to be in the allow-set;
- TCP to an allow-set address on ports **80, 443 and 22** (`SECMAN_EGRESS_PORTS`;
  22 is there so `git push` over SSH works — drop it if you only use HTTPS
  remotes).

`INPUT` policy is `DROP`, opened only for the four published ports and for
replies. The last rule before the reject is a rate-limited `LOG`, which is the
only record of what was refused now that there is no proxy log.

Denials `REJECT` rather than `DROP`, so a blocked tool gets an immediate
`connection refused` instead of hanging for a two-minute timeout — the difference
between an obvious allowlist gap and a mystery.

### Refreshing

Because the policy is a snapshot of DNS, and `registry.npmjs.org`,
`api.anthropic.com` and `repo.maven.apache.org` all move between addresses within
hours, the entrypoint re-resolves and rebuilds **every 30 minutes**
(`SECMAN_EGRESS_REFRESH_MINUTES`, `0` disables). Without it, a container left
running overnight starts failing downloads for reasons that look nothing like a
firewall.

On demand:

```bash
./scripts/container/secman-container.sh egress refresh
```

With ipset the rebuild is an atomic swap and never interrupts traffic. With plain
rules there is a sub-second window in which *new* connections are refused;
established ones are unaffected. A failed refresh leaves the previous ruleset in
place — the safe direction.

### Inspecting

```bash
./scripts/container/secman-container.sh egress show         # policy, backend, allowlist
./scripts/container/secman-container.sh egress test HOST... # probe, through the real path
./scripts/container/secman-container.sh egress log          # what was refused
./scripts/container/secman-container.sh egress unresolved   # names that resolve to nothing
```

`egress unresolved` is worth a look after any allowlist edit: a name that resolves
to nothing is a name the container cannot reach, whether because it is misspelt
or because DNS was failing when the set was built.

> The per-packet `LOG` output is suppressed by the kernel outside its initial
> network namespace, so `egress log` may show only the **deny counter** and no
> detail. The counter is a rule statistic and is never suppressed, so it still
> answers "is anything being refused?".

### The self-test

The container **refuses to start** if a host nobody allowlisted turns out to be
reachable. A firewall nobody tested is a firewall nobody has, and starting anyway
would hand the agents an environment whose containment is a guess.

Two further guards fail closed rather than quietly degrading: an allowlist that
renders to nothing is refused, and if more than half the names fail to resolve
the firewall refuses to install (that is DNS being broken, not an allowlist
problem, and the resulting policy would reach almost nothing).
`SECMAN_EGRESS_ALLOW_PARTIAL_DNS=1` overrides the latter.

### What is allowed, and why

`docker/apple-container/egress/allowlist.txt` is the list, grouped by purpose and
annotated. In summary:

| Purpose | Examples |
|---|---|
| Claude Code | `api.anthropic.com`, `console.anthropic.com`, `statsig.anthropic.com`, `claude.ai`, `sentry.io` |
| Kimi CLI | `api.moonshot.ai`, `api.moonshot.cn`, `code.kimi.com` |
| Proton Pass | `account.proton.me`, `pass-api.proton.me`, `api.protonmail.ch` |
| Git / GitHub | the published ranges from `api.github.com/meta` |
| Gradle / Maven | `services.gradle.org`, `plugins.gradle.org`, `repo.maven.apache.org`, `repo1.maven.org`, `dl.google.com`, `download.jetbrains.com`, `packages.adoptium.net` |
| Node / npm | `registry.npmjs.org`, `nodejs.org` |
| Go | `proxy.golang.org`, `sum.golang.org`, `storage.googleapis.com` |
| Python / uv | `pypi.org`, `files.pythonhosted.org`, `astral.sh` |
| Playwright | `cdn.playwright.dev`, `playwright.download.prss.microsoft.com` |
| Debian / MariaDB apt | `deb.debian.org`, `dlm.mariadb.com` |
| Secman's own outbound calls | `endoflife.date`, `api.crowdstrike.com` (+ regional), `openrouter.ai`, `hooks.slack.com`, `api.telegram.org`, `acme-v02.api.letsencrypt.org` |

Those last entries are the hosts the *application* reaches while you exercise it.
Each is already SSRF-guarded server-side (`CLAUDE.md` §A10); the allowlist is the
second layer, not the first.

### Adding an entry

**Write exact hostnames.** A leading dot means nothing here — there is no
wildcard matching to attach it to, so `.example.com` covers no subdomain that
`example.com` does not. Addresses and CIDRs are accepted verbatim.

Three ways, in increasing permanence:

```bash
# one container lifetime
./scripts/container/secman-container.sh up --src "$PWD" --allow-domain nexus.example.com

# persistent, survives an image rebuild
./scripts/container/secman-container.sh root
root@secman-box:/# echo 'nexus.example.com' >> /etc/secman-dev/egress/allowlist.local.txt
root@secman-box:/# refresh-egress

# permanent for everyone — edit docker/apple-container/egress/allowlist.txt and rebuild
```

**Two things are deliberately not allowlisted by default.** Your `SECMAN_HOST`,
if `pass-cli` points the CLI and the tests at a shared instance rather than at the
container's own stack; and AWS, which is regional and cannot be enumerated — add
the exact endpoints you use (`s3.eu-central-1.amazonaws.com`, and so on).

## Ports

| Container | Purpose | Host |
|---|---|---|
| 8080 | Micronaut backend | 8080 |
| 4321 | Astro dev server | 4321 |
| 443 | nginx, TLS, fronts both | 443 (see below) |
| 3306 | MariaDB 11.4 | 3306 (`--no-db` turns it off) |

The **443 front door** exists because the app's shape depends on it: the
`secman_auth` cookie is `Secure`, OAuth redirects and CORS are origin-sensitive,
and the SSE endpoints need `proxy_buffering off`. Serving the dev stack over
plain HTTP produces a login that silently never persists. nginx therefore fronts
both services on one HTTPS origin, exactly as your reverse proxy does on the Mac,
with a self-signed certificate whose subject you set with `--tls-host`.

> **macOS reserves ports below 1024 for root.** `container`'s port-forwarding
> helper runs as you, so publishing host port 443 usually fails. The start script
> probes for this and falls back to publishing on **8443**, telling you so. The
> container still serves TLS on 443 — reach it there directly on the container's
> own IP, which `... status` prints. If you point a hostname at the container
> (e.g. `secman.covestro.net` in `/etc/hosts`), use that IP and you get real :443.

---

## `pass-cli` inside the container

`pass-cli` is installed in the image and configured for a container:

```sh
PROTON_PASS_KEY_PROVIDER=file
PROTON_PASS_SESSION_DIR=$HOME/.local/share/proton-pass-cli/.session
```

The `file` key provider is the documented container option — there is no OS
keyring inside the VM for the default provider to talk to. The session directory
is on the `secman-dev-home` named volume, so **`pass-cli login` is a one-time
step**: it survives container restarts, and it never touches your Mac's
filesystem or its Keychain.

Two ways in:

```bash
# interactive, once, inside the container
pass-cli login --interactive

# or a personal access token, picked up from your Mac shell by `up`
export PROTON_PASS_PERSONAL_ACCESS_TOKEN='pst_…::TOKENKEY'
./scripts/container/secman-container.sh up --src "$PWD"
```

A token passed that way is stored in the container's own configuration, so prefer
a scoped token over your account credentials. The browser-based `pass-cli login`
flow is *not* the one to use here — its callback lands inside the container,
where your Mac's browser cannot reach it.

Once logged in, everything canonical works unchanged: `./scripts/startbackenddev.sh`,
`./scripts/startfrontenddev.sh`, `./scripts/secman …`, `./tests/e2e/run-e2e.sh`.

---

## Apple-specific behaviour

Things that are true of Apple's `container` and not of Docker, all of which this
setup either handles or works around:

1. **One VM per container.** Each container is a full Linux VM with its own
   kernel and its own IP, not a namespace on a shared daemon kernel. That is why
   an in-container `iptables` policy is meaningful here, and why the container
   has no neighbours to be exposed to.
2. **No nftables.** The default kernel omits the pieces `nft` needs. The
   firewall pins `iptables-legacy` explicitly, because a silent fall-through to
   the nft backend would fail *open*. `ipset` is probed the same way and the
   firewall degrades to plain rules without it.
3. **Incomplete IPv6.** The entrypoint disables IPv6 and sets all `ip6tables`
   policies to `DROP`. Half-working IPv6 is worse than none: it creates egress
   paths the v4 ruleset does not cover.
4. **No privileged mode.** Containers start with a restricted default capability
   set. The firewall needs `--cap-add NET_ADMIN --cap-add NET_RAW`, which the
   start script adds — and nothing more.
5. **`--publish` needs macOS 26.** On macOS 15 port forwarding does not exist and
   container networking is heavily limited. The script detects this and tells you
   to use the container's IP.
6. **Options must precede the image name.** Anything after it is treated as
   arguments for the container process — a `-p` in the wrong place silently
   becomes a program argument instead of a published port.
7. **Host uid is not 1000.** virtiofs presents the Mac's ownership verbatim, and
   a macOS account is uid 501. The entrypoint creates `dev` with the uid and gid
   that actually own `/workspace`, so files written inside stay usable outside.
8. **No compose.** One container, one entrypoint that supervises the services it
   owns. `devctl` manages them from inside; the application stack is still
   started by the repo's own scripts.
9. **Anonymous volumes are not removed with `--rm`.** This setup uses only named
   volumes, so there is nothing to leak — but `destroy` is what removes them.
10. **The builder is a container too.** `build` starts it with 4 CPUs and 8 GB;
    the defaults make the image's downloads and unpacks needlessly slow.

---

## Sizing

`gradle.properties` configures a **5632m** Gradle daemon heap, and KSP runs
inside that daemon. Anything under roughly 8 GB turns `./gradlew build` into an
OOM loop, so `up` defaults to `--memory 10g --cpus 6`. Lower it only if you are
not building the backend.

---

## Command reference

```
./scripts/container/secman-container.sh <command>

  build [args...]                  build the dev image
  up   [options]                   start the shielded container
  shell                            login shell inside it (user: dev)
  claude [args...]                 Claude Code, inside the shield
  kimi   [args...]                 Kimi CLI, inside the shield
  run <cmd...>                     run one command inside it
  root [cmd...]                    root shell inside it
  status                           services, ports, egress mode, container IP
  logs [-f]                        start-up log
  egress [show|log N|refresh|test <host>...]
  down                             stop and remove the container, keep volumes
  destroy                          down, and delete the volumes too

Options for `up`:
  --src PATH        the ONLY host path the container can see (default: this repo)
  --name NAME       container name (default: secman-dev)
  --memory SIZE     default 10g
  --cpus N          default 6
  --no-db           do not run the in-container MariaDB; :3306 stays closed
  --no-tls          do not start the :443 front door
  --tls-host HOST   certificate subject / SAN (default: localhost)
  --tls-port PORT   host port for the container's :443 (default: 443)
  --allow-domain D  add D to the egress allowlist (repeatable)
```

Inside the container, `devctl` controls what the container owns:

```
devctl status                  what is running, and how egress is enforced
devctl tls   start|stop        the :443 front door
devctl db    start|stop        MariaDB
devctl egress refresh|log      reload or inspect the egress policy
egress-check [host...]         show the allowlist, or probe specific hosts
```

---

## What this does and does not protect

**It does** stop an agent from reading your Mac's filesystem beyond the one
shared directory, from reaching hosts nobody put on the allowlist, from
exfiltrating to an arbitrary endpoint, and from touching your Keychain or your
host's network services.

**It does not** protect against:

- anything reachable *through* an allowlisted host — an agent can still push to
  GitHub, and an npm package it installs still runs with the container's reach;
- **anything else sharing an allowlisted address.** Filtering is by address, so
  allowing `api.anthropic.com` allows whatever else answers on that CDN address.
  This is the cost of running without a name-aware gate, and it is not fixable
  at the packet layer;
- secrets you put into the container — `pass-cli` resolves real credentials
  inside it, and anything running there can read what a shell can read;
- the source tree. `/workspace` is read-write by design; that is the point.

Treat it as containment for accidents and blast-radius reduction, not as a
sandbox for hostile code.

---

## Troubleshooting

| Symptom | Cause / fix |
|---|---|
| `egress self-test FAILED` and the container exits | A non-allowlisted host was reachable, so the firewall is not containing the container. Check `container logs secman-dev` — usually `iptables` could not install rules because `NET_ADMIN` was not granted. |
| `refusing to install a policy that reaches almost nothing` | More than half the allowlist names failed to resolve — DNS is broken, not the allowlist. Fix DNS, or override with `SECMAN_EGRESS_ALLOW_PARTIAL_DNS=1`. |
| A download that worked an hour ago now fails | The address rotated out of the allow-set. `... egress refresh`. If it keeps happening, lower `SECMAN_EGRESS_REFRESH_MINUTES`. |
| A build fails with `connection refused` to a real host | The host is not allowlisted. Confirm with `... egress test <host>`, then add it (see [Adding an entry](#adding-an-entry)). |
| `... egress show` reports backend **rules** | The kernel has no `ipset`. Expected on a trimmed kernel; the policy is identical, matching is just linear. |
| `pass-cli: not signed in` | `... shell` then `pass-cli login --interactive`. The session persists after that. |
| Host port 443 not listening | macOS reserves it for root; the script published 8443 instead and said so. Use the container IP from `... status` for real 443. |
| `./gradlew build` OOMs | `--memory` below ~8 GB. Restart with `--memory 10g`. |
| Files written inside show as owned by someone else on the Mac | `/workspace` was owned by root when the container started. Recreate it with `down` + `up`. |
| Nothing forwards, macOS 15 | `--publish` needs macOS 26. Use the container IP. |
| `container: command not found` | Install from <https://github.com/apple/container/releases>. |

---

## Files

```
docker/apple-container/
  Containerfile                     the image
  entrypoint.sh                     PID 1: user alignment, firewall, self-test, refresher
  egress/allowlist.txt              the allowlist, annotated
  egress/init-egress-firewall.sh    the iptables policy (ipset or plain rules)
  nginx/secman-dev.conf             the :443 front door
  profile.d/secman-dev.sh           toolchain and pass-cli environment
  bin/devctl                        in-container service control
  bin/egress-check                  inspect and probe the egress policy
  bin/render-egress-allowlist       the one list the firewall reads
  bin/refresh-egress                re-resolve and rebuild without a restart
scripts/container/secman-container.sh   the host-side driver (macOS)
```
