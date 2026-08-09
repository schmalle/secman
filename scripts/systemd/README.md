# scripts/systemd — EXAMPLE unit for the compiled backend JAR + AWS Secrets Manager

Two example files, meant to be copied and adjusted rather than used as-is:

| File | Role |
|---|---|
| `secman-backend.service.example` | The systemd unit. Paths, user, region, JVM options, hardening. |
| `secman-backend-aws.sh` | Its `ExecStart` target. Fetches the secret, exports the env, `exec`s `java -jar`. |

This is the **compiled-JAR** deployment. The two existing variants stay where
they were and are not replaced by this one:

- `docs/DEPLOYMENT.md` § systemd — JAR + `EnvironmentFile=/etc/secman/backend.env`.
- `docs/AWS.md` § systemd alternative — `scripts/startbackenddevaws.sh` (gradle run), for dev boxes.

## Why a launcher instead of `EnvironmentFile=`

`EnvironmentFile=` needs the DB password, `JWT_SECRET` and
`SECMAN_ENCRYPTION_PASSWORD` to exist as plaintext on disk. The launcher pulls
them from Secrets Manager into its own process environment and `exec`s the JVM,
so nothing is written out and the JVM still receives a `SIGTERM` straight from
systemd on stop.

It sources `scripts/lib/aws-secrets.sh` — the same mapping every other
`*aws.sh` launcher uses — so it stays in step with the secret schema for free,
then adds the keys that mapping does not cover (crypto material, SMTP,
`FRONTEND_URL`).

## Prerequisites

- `aws` CLI v2 and `jq` on the host.
- A JRE at `/usr/bin/java` (override with `SECMAN_JAVA_BIN`). Java 25 per `CLAUDE.md`.
- An instance role / IRSA with `secretsmanager:GetSecretValue` on **that one
  secret ARN** — no access key in the unit file.
- The shadow JAR built:
  ```bash
  ./gradlew :backendng:clean :backendng:shadowJar -x test
  # -> src/backendng/build/libs/backendng-0.1-all.jar
  ```

## Secret keys

Everything in `docs/AWS.md` § Secret keys reference, plus these, which the
shared mapping does not export because the dev/CLI launchers do not need them:

| JSON key | Exported as | Required |
|---|---|---|
| `JWT_SECRET` | `JWT_SECRET` | yes |
| `SECMAN_ENCRYPTION_PASSWORD` | `SECMAN_ENCRYPTION_PASSWORD` | yes |
| `SECMAN_ENCRYPTION_SALT` | `SECMAN_ENCRYPTION_SALT` | yes |
| `FRONTEND_URL` | `FRONTEND_URL` | no |
| `SMTP_HOST` / `SMTP_PORT` / `SMTP_USERNAME` / `SMTP_PASSWORD` / `SMTP_FROM_ADDRESS` / `SMTP_FROM_NAME` | same names | no |

Absent keys are skipped, so optional ones fall back to `application.yml`. The
launcher exits `78` (`EX_CONFIG`) naming any missing **required** key — names
only, never values.

Generate the crypto material once and store it:

```bash
openssl rand -base64 48   # JWT_SECRET
openssl rand -hex 32      # SECMAN_ENCRYPTION_PASSWORD
openssl rand -hex 8       # SECMAN_ENCRYPTION_SALT  (exactly 16 hex chars)
```

`JWT_SECRET` is **stored**, not regenerated per start the way
`startbackenddev.sh` does — a fresh secret on every restart invalidates every
session. Rotating either `SECMAN_ENCRYPTION_*` value orphans already-encrypted
data (`docs/ENVIRONMENT.md`).

## Install

```bash
sudo install -D -m 0755 -o secman -g secman \
     scripts/systemd/secman-backend-aws.sh /opt/secman/scripts/systemd/secman-backend-aws.sh
sudo install -m 0644 \
     scripts/systemd/secman-backend.service.example /etc/systemd/system/secman-backend.service

# Adjust: User/Group, WorkingDirectory, SECMAN_JAR, SECMAN_AWS_SECRET_ID, AWS_REGION.
sudo systemctl edit --full secman-backend.service

sudo install -d -o secman -g secman /var/log/secman /opt/secman/logs
sudo systemctl daemon-reload
sudo systemctl enable --now secman-backend.service
journalctl -u secman-backend -f
```

Smoke-test the launcher on its own first — it prints the failure reason before
systemd's restart loop can bury it:

```bash
sudo -u secman SECMAN_AWS_SECRET_ID=secman/prod ./scripts/systemd/secman-backend-aws.sh
```

## Notes on the hardening block

- `ProtectSystem=strict` makes `/` read-only, so every writable path is named in
  `ReadWritePaths` (`/var/log/secman`, `/opt/secman/logs`). Add the heap-dump
  directory there too if you move `-XX:HeapDumpPath`.
- `ProtectHome=true` hides `~/.sdkman` and `~/.aws` from the service — deliberate:
  `SECMAN_JAVA_BIN` is absolute and AWS auth comes from the instance role. If you
  really need `~/.aws`, use `ProtectHome=read-only`.
- `After=network-online.target` rather than `network.target`: the launcher makes
  an HTTPS call before the JVM starts.
- `StartLimitBurst=5` / `StartLimitIntervalSec=300` stop a misconfigured secret
  from turning into a Secrets Manager request loop.
- Drop `Requires=mariadb.service` / `After=…mariadb.service` when the database
  is RDS or otherwise off-host.

## Frontend

Unchanged — `docs/DEPLOYMENT.md` § systemd has the `secman-frontend.service`
unit, and it has no secrets to fetch.
