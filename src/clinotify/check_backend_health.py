#!/usr/bin/env python3
"""
Watch secman's DB-aware /health endpoint and restart the secman-backend
systemd service when the database connection is lost, so a DB outage (or a
HikariCP pool that never recovers after one) doesn't leave the backend stuck
returning errors indefinitely.

Queries
    GET {secman-url}/health
which returns JSON like {"status": "UP"|"DOWN", "checks": {"database": "UP"|"DOWN"}}.
A non-200 response, a transport error, or checks.database != "UP" counts as
one failed probe.

To avoid restarting on a single transient blip, a restart only fires after
--fail-threshold consecutive failed probes. To avoid restart storms while the
database itself stays down for a long stretch (restarting the backend can't
fix a dead database), once a restart has fired, further restarts are
suppressed for --restart-cooldown-minutes; failures are still logged/alerted
during the cooldown. Small JSON state (consecutive-failure count, last-restart
timestamp, last-known-up flag) persists at --state-file between runs, since
each invocation is a single cron tick.

Exit codes:
    0  OK, probe succeeded (or failed but below fail-threshold)
    1  Transport / parse error, cannot determine backend health
    2  Invalid arguments
    3  Alert triggered (restart fired, or DOWN and still in cooldown)
"""

import argparse
import json
import os
import ssl
import subprocess
import sys
import urllib.error
import urllib.request
from datetime import datetime, timedelta


def probe_health(base_url: str, insecure: bool, timeout: int):
    """Returns (ok: bool, detail: str). ok is True only on HTTP 200 with
    checks.database == "UP"."""
    endpoint = base_url.rstrip("/") + "/health"
    ctx = ssl._create_unverified_context() if insecure else None
    req = urllib.request.Request(endpoint, headers={"Accept": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=timeout, context=ctx) as resp:
            body = resp.read().decode("utf-8")
            status_code = resp.status
    except urllib.error.HTTPError as e:
        status_code = e.code
        body = e.read().decode("utf-8") if e.fp else ""
    try:
        parsed = json.loads(body) if body else {}
    except ValueError:
        parsed = {}
    db_status = parsed.get("checks", {}).get("database", "UNKNOWN")
    ok = status_code == 200 and db_status == "UP"
    detail = f"HTTP {status_code}, checks.database={db_status}"
    return ok, detail


def send_telegram(token: str, chat_id: str, text: str, timeout: int) -> None:
    url = f"https://api.telegram.org/bot{token}/sendMessage"
    payload = json.dumps({"chat_id": chat_id, "text": text}).encode("utf-8")
    req = urllib.request.Request(
        url,
        data=payload,
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        if resp.status != 200:
            raise RuntimeError(f"Telegram API returned HTTP {resp.status}")


def load_state(path: str) -> dict:
    try:
        with open(path, "r") as f:
            return json.load(f)
    except (FileNotFoundError, ValueError):
        return {}


def save_state(path: str, state: dict) -> None:
    tmp_path = path + ".tmp"
    with open(tmp_path, "w") as f:
        json.dump(state, f)
    os.replace(tmp_path, path)


def restart_backend(service_name: str, dry_run: bool) -> None:
    if dry_run:
        return
    subprocess.run(["systemctl", "restart", service_name], check=True)


def log(message: str) -> None:
    try:
        subprocess.run(["logger", "-t", "secman-backend-monitor", message], check=False)
    except FileNotFoundError:
        pass
    print(message, file=sys.stderr)


def main() -> int:
    parser = argparse.ArgumentParser(
        description=__doc__,
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument(
        "--url",
        required=True,
        help="Base URL of the secman instance (e.g. https://secman.example.com)",
    )
    parser.add_argument(
        "--service-name",
        default="secman-backend",
        help="systemd service to restart on failure (default: secman-backend)",
    )
    parser.add_argument(
        "--fail-threshold",
        type=int,
        default=2,
        help="Consecutive failed probes required before restarting (default: 2)",
    )
    parser.add_argument(
        "--restart-cooldown-minutes",
        type=int,
        default=15,
        help="Minimum minutes between restarts, to avoid restart storms "
             "while the database itself is down (default: 15)",
    )
    parser.add_argument(
        "--state-file",
        default="/var/tmp/secman-backend-monitor.json",
        help="Path to persist consecutive-failure count and last-restart time",
    )
    parser.add_argument(
        "--telegram-bot-token",
        default=os.environ.get("TELEGRAM_BOT_TOKEN"),
        help="Telegram bot token (default: $TELEGRAM_BOT_TOKEN). Optional — "
             "restarts still happen without alerting if unset.",
    )
    parser.add_argument(
        "--telegram-chat-id",
        default=os.environ.get("TELEGRAM_CHAT_ID"),
        help="Telegram chat id (default: $TELEGRAM_CHAT_ID)",
    )
    parser.add_argument(
        "--insecure",
        action="store_true",
        help="Disable TLS verification when calling the secman endpoint",
    )
    parser.add_argument(
        "--timeout",
        type=int,
        default=10,
        help="HTTP timeout in seconds (default: 10)",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Log/alert as usual but skip the actual systemctl restart",
    )
    parser.add_argument(
        "--verbose",
        "-v",
        action="store_true",
        help="Print diagnostic info to stdout",
    )
    args = parser.parse_args()

    if args.fail_threshold < 1:
        print("error: --fail-threshold must be >= 1", file=sys.stderr)
        return 2
    if args.restart_cooldown_minutes < 0:
        print("error: --restart-cooldown-minutes must be >= 0", file=sys.stderr)
        return 2

    def alert(text: str) -> None:
        if not (args.telegram_bot_token and args.telegram_chat_id):
            return
        try:
            send_telegram(args.telegram_bot_token, args.telegram_chat_id, text, args.timeout)
        except (urllib.error.URLError, TimeoutError, OSError, RuntimeError) as e:
            print(f"error: telegram send failed: {e}", file=sys.stderr)

    state = load_state(args.state_file)
    consecutive_failures = int(state.get("consecutive_failures", 0))
    last_restart_raw = state.get("last_restart")
    was_up = bool(state.get("was_up", True))

    try:
        ok, detail = probe_health(args.url, args.insecure, args.timeout)
    except (urllib.error.URLError, TimeoutError, OSError) as e:
        print(f"error: failed to query {args.url}/health: {e}", file=sys.stderr)
        return 1

    if args.verbose:
        print(f"probe result: {detail}")

    if ok:
        if not was_up and consecutive_failures > 0:
            msg = f"secman recovered: {args.url}/health is UP again ({detail})."
            log(msg)
            alert(msg)
        save_state(args.state_file, {"consecutive_failures": 0, "last_restart": last_restart_raw, "was_up": True})
        if args.verbose:
            print("OK: backend healthy")
        return 0

    consecutive_failures += 1
    if args.verbose:
        print(f"failure {consecutive_failures}/{args.fail_threshold} (detail: {detail})")

    if consecutive_failures < args.fail_threshold:
        save_state(args.state_file, {
            "consecutive_failures": consecutive_failures,
            "last_restart": last_restart_raw,
            "was_up": False,
        })
        log(f"secman-backend-monitor: probe failed ({detail}), "
            f"{consecutive_failures}/{args.fail_threshold} consecutive")
        return 0

    now = datetime.now()
    last_restart = datetime.fromisoformat(last_restart_raw) if last_restart_raw else None
    in_cooldown = last_restart is not None and now - last_restart < timedelta(minutes=args.restart_cooldown_minutes)

    if in_cooldown:
        msg = (f"secman alert: {args.url}/health still DOWN ({detail}) after "
               f"{consecutive_failures} consecutive failures, but restart cooldown "
               f"is active (last restart {last_restart.isoformat()}). Not restarting again.")
        log(msg)
        alert(msg)
        save_state(args.state_file, {
            "consecutive_failures": consecutive_failures,
            "last_restart": last_restart_raw,
            "was_up": False,
        })
        return 3

    msg = (f"secman alert: {args.url}/health DOWN ({detail}) for "
           f"{consecutive_failures} consecutive checks. "
           f"{'Would restart' if args.dry_run else 'Restarting'} {args.service_name}.")
    log(msg)
    try:
        restart_backend(args.service_name, args.dry_run)
    except subprocess.CalledProcessError as e:
        err = f"error: failed to restart {args.service_name}: {e}"
        log(err)
        alert(err)
        save_state(args.state_file, {
            "consecutive_failures": consecutive_failures,
            "last_restart": last_restart_raw,
            "was_up": False,
        })
        return 1

    alert(msg)
    save_state(args.state_file, {
        "consecutive_failures": 0,
        "last_restart": now.isoformat(),
        "was_up": False,
    })
    print(msg)
    return 3


if __name__ == "__main__":
    sys.exit(main())
