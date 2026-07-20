# Runbook: Restarting secman After Database Connection Loss

Real incident this runbook exists for: the backend's HikariCP pool lost its connection to
MariaDB and never recovered on its own — the process stayed up, kept accepting requests, and
every DB-backed endpoint failed indefinitely until someone noticed and restarted it by hand.
This doc is the step-by-step response for that failure mode. It assumes the **detection
scripts already exist and are wired up** (`GET /health`, `check_backend_health.py`) — this is
the "what do I do when they fire" playbook, not a setup guide.

For general symptom→fix triage see [`TROUBLESHOOTING.md`](./TROUBLESHOOTING.md). For the
watchdog's own setup/flags see [`../src/clinotify/README.md`](../src/clinotify/README.md) and
the "Monitor" section of [`DEPLOYMENT.md`](./DEPLOYMENT.md).

## 1. Confirm it's actually DB connection loss

Don't restart blind — confirm the failure mode first, since "backend down" and "backend up but
DB unreachable" need different fixes.

```bash
curl -s http://localhost:8080/health | jq .
```

- `{"status":"UP","checks":{"database":"UP"},...}` (HTTP 200) → backend is fine, the reported
  problem is something else. Stop here and use `TROUBLESHOOTING.md` instead.
- `{"status":"DOWN","checks":{"database":"DOWN"},...}` (HTTP 503) → **this is the case this
  runbook covers.** The endpoint runs a bounded ~3s `SELECT 1` on a separate thread
  (independent of HikariCP's own 30s connection-timeout), specifically so a wedged pool is
  detected fast instead of hanging. Continue to step 2.
- Connection refused / timeout → the backend process itself is down, not just its DB
  connection. Skip to step 4 (restart backend) after checking `journalctl -u secman-backend`
  for a crash, then re-run this health check once it's back up.

If the automated watchdog (`check_backend_health.py`, see `src/clinotify/README.md`) already
fired, it will have logged one of:
- restarted the backend after `--fail-threshold` consecutive failed probes, or
- alerted (via Telegram, if configured) that it's still `DOWN` but restarts are suppressed
  because it's inside `--restart-cooldown-minutes` of its last restart.

Check its log/state before doing anything manual, so you don't fight a restart that's already
in flight:

```bash
tail -50 /var/log/secman-backend-monitor.log
cat /var/tmp/secman-backend-monitor.json      # consecutive_failures, last_restart, was_up
```

## 2. Check whether MariaDB itself is up

Restarting the backend does nothing if the database is the thing that's actually down.

```bash
sudo systemctl status mariadb
mysql -u secman -p secman -e "SELECT 1"
```

- **MariaDB is down** → start it first, then re-check `/health` before touching the backend at
  all — the backend may recover the connection on its own once the DB is reachable again
  (HikariCP will retry), without needing a restart:
  ```bash
  sudo systemctl start mariadb
  sudo systemctl status mariadb
  sleep 5
  curl -s http://localhost:8080/health | jq .
  ```
  If `/health` comes back `UP` on its own, **stop — no backend restart needed.** If it's still
  `DOWN` after MariaDB is confirmed up, continue to step 3/4; the pool itself is wedged and
  needs a restart even though the DB is now reachable.
- **MariaDB is up** but `/health` still reports `database: DOWN` → the DB is reachable but the
  backend's pool didn't recover (the known failure mode). Go straight to step 4.

If MariaDB itself won't start, that's a database incident, not a secman one — check
`sudo journalctl -u mariadb -n 100`, disk space (`df -h`), and MariaDB's own error log
(typically `/var/log/mariadb/` or `/var/log/mysql/`) before proceeding.

## 3. Rule out non-DB causes of a wedged pool

Quick checks before restarting, since they change what you do afterward:

```bash
# Is the pool exhausted rather than the DB unreachable? (MariaDB side)
mysql -u secman -p secman -e "SHOW PROCESSLIST;" | grep -c secman
# secman's pool caps at maximum-pool-size: 20 (application.yml) — a count near/at 20
# with most connections idle/sleeping for a long time points at a leak, not an outage.

# Disk full on the DB host causes MariaDB to refuse writes without necessarily going down
df -h
```

If the pool is exhausted rather than the DB being unreachable, a backend restart still clears
it (it recreates the pool from scratch), but flag it for follow-up — a leak will recur.

## 4. Restart the backend

### Production (systemd)

```bash
sudo systemctl restart secman-backend
sudo systemctl status secman-backend
sudo journalctl -u secman-backend -f     # watch startup; Ctrl-C once it's settled
```

`./scripts/restartbackend.sh` and `./scripts/stopbackend.sh` / a manual
`systemctl start secman-backend` do the same thing; the automated watchdog
(`check_backend_health.py`) runs `systemctl restart secman-backend` under the hood too, so its
cooldown state file is what to check before you do this manually (step 1) to avoid duplicate
restarts.

The frontend does **not** need restarting for a backend/DB issue — it's a separate systemd
unit (`secman-frontend`) with no DB connection of its own. Only restart it if the frontend
health check (`curl http://localhost:4321/`) is also failing.

### Local dev environment

There is no systemd service in dev; the canonical scripts manage the process bound to the port
directly. **Never** call `./gradlew run` or `npm run dev` directly — always go through the
scripts, since they source required secrets from `pass-cli`.

```bash
./scripts/stopbackenddev.sh        # kills whatever is listening on :8080
./scripts/startbackenddev.sh       # must run outside any sandbox — needs pass-cli
```

Frontend restart is not required for a backend-only DB issue, but if it's also unresponsive:

```bash
./scripts/stopfrontenddev.sh       # kills whatever is listening on :4321
./scripts/startfrontenddev.sh      # must run outside any sandbox — needs pass-cli
```

## 5. Verify recovery

```bash
curl -s http://localhost:8080/health | jq .
# expect: {"status":"UP","service":"secman-backend-ng","version":"0.1","checks":{"database":"UP"}}

# Confirm the app is actually usable end-to-end, not just the health probe:
curl -s -X POST http://localhost:8080/api/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"<admin-user>","password":"<admin-pass>"}' | jq .
```

If `/health` reports `UP` but real requests still fail (500s), check
`journalctl -u secman-backend -n 100` (or the dev console output) for a stack trace unrelated
to the DB outage — don't assume the incident is closed on `/health` alone.

## 6. Reset watchdog/alert state (production only)

If the automated watchdog fired and alerted, its cooldown will otherwise suppress a *real*
restart if the DB drops again soon after. Once you've manually confirmed recovery, either:

- let the next scheduled probe (every `*/2 * * * *`, see `DEPLOYMENT.md`) observe the `UP`
  state itself — it self-clears `consecutive_failures` and posts a "recovered" alert
  automatically, no action needed; or
- if you need it to reset immediately (e.g. to re-arm before the cooldown window expires),
  clear the state file so the next probe starts clean:
  ```bash
  rm -f /var/tmp/secman-backend-monitor.json
  ```

## 7. Post-incident follow-up

- Note the incident time and duration — cross-reference with MariaDB's own log for the actual
  root cause (network blip, MariaDB OOM/crash, disk full, max_connections exhausted on the DB
  side, etc.). A backend restart clears the symptom, not the cause.
- If the pool was exhausted (step 3) rather than the DB actually going away, look for a
  connection leak introduced by a recent change — `maximum-pool-size: 20` /
  `connection-timeout: 30000` / `idle-timeout: 1800000` are in
  `src/backendng/src/main/resources/application.yml` under `datasources.default` if tuning is
  warranted.
- Confirm the watchdog cron/alert path is still installed and pointed at the right URL —
  this incident is exactly what it exists to catch automatically next time:
  ```cron
  */2 * * * * TELEGRAM_BOT_TOKEN=… TELEGRAM_CHAT_ID=… \
    /opt/secman/src/clinotify/check_backend_health.py \
    --url https://secman.example.com --fail-threshold 2 --restart-cooldown-minutes 15 \
    >> /var/log/secman-backend-monitor.log 2>&1
  ```

## Quick reference

| Situation | Action |
|---|---|
| `/health` → `UP` | No action, not a DB issue |
| `/health` → `DOWN`, MariaDB down | Start MariaDB, re-check `/health` before restarting backend |
| `/health` → `DOWN`, MariaDB up | Restart `secman-backend` (prod: `systemctl restart secman-backend`; dev: `stopbackenddev.sh` + `startbackenddev.sh`) |
| Connection refused on `:8080` | Backend process itself is down — check logs, restart, then re-verify `/health` |
| Watchdog already restarted (check state file) | Don't restart again; wait for cooldown or verify manually first |
| Pool near `maximum-pool-size` with idle connections | Restart clears it now; investigate a leak afterward |
