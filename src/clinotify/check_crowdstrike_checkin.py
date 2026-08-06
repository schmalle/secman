#!/usr/bin/env python3
"""
Check secman's CrowdStrike last-checkin freshness and alert via Telegram
if the last import is older than a configurable threshold (or "never").

Queries the public endpoint
    GET {secman-url}/api/crowdstrike/last-checkin
which returns either an ISO-8601 timestamp (text/plain) or the literal
string "never". If the checkin is older than --max-age-minutes, or the
endpoint returns "never", a message is posted to the configured
Telegram chat via the Bot API.

Exit codes:
    0  OK, last checkin is within the threshold
    1  Transport / parse error (nothing sent)
    2  Invalid arguments
    3  Alert triggered (checkin stale or "never")
"""

import argparse
import json
import os
import ssl
import sys
import urllib.error
import urllib.request
from datetime import datetime


def fetch_last_checkin(base_url: str, insecure: bool, timeout: int) -> str:
    endpoint = base_url.rstrip("/") + "/api/crowdstrike/last-checkin"
    ctx = ssl._create_unverified_context() if insecure else None
    req = urllib.request.Request(endpoint, headers={"Accept": "text/plain"})
    with urllib.request.urlopen(req, timeout=timeout, context=ctx) as resp:
        return resp.read().decode("utf-8").strip()


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
        "--max-age-minutes",
        type=int,
        required=True,
        help="Alert if the last checkin is older than this many minutes, "
             "or if the endpoint returns 'never'",
    )
    parser.add_argument(
        "--telegram-bot-token",
        default=os.environ.get("TELEGRAM_BOT_TOKEN"),
        help="Telegram bot token (default: $TELEGRAM_BOT_TOKEN)",
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
        default=15,
        help="HTTP timeout in seconds (default: 15)",
    )
    parser.add_argument(
        "--verbose",
        "-v",
        action="store_true",
        help="Print diagnostic info to stdout",
    )
    args = parser.parse_args()

    if not args.telegram_bot_token:
        print("error: --telegram-bot-token or $TELEGRAM_BOT_TOKEN is required",
              file=sys.stderr)
        return 2
    if not args.telegram_chat_id:
        print("error: --telegram-chat-id or $TELEGRAM_CHAT_ID is required",
              file=sys.stderr)
        return 2
    if args.max_age_minutes < 0:
        print("error: --max-age-minutes must be >= 0", file=sys.stderr)
        return 2

    try:
        raw = fetch_last_checkin(args.url, args.insecure, args.timeout)
    except (urllib.error.URLError, TimeoutError, OSError) as e:
        print(f"error: failed to query {args.url}: {e}", file=sys.stderr)
        return 1

    if args.verbose:
        print(f"endpoint response: {raw!r}")

    # "never" -> always alert
    if raw == "never":
        msg = (f"secman alert: CrowdStrike has NEVER checked in "
               f"(source: {args.url}).")
        try:
            send_telegram(args.telegram_bot_token, args.telegram_chat_id,
                          msg, args.timeout)
        except (urllib.error.URLError, TimeoutError, OSError,
                RuntimeError) as e:
            print(f"error: telegram send failed: {e}", file=sys.stderr)
            return 1
        print(msg)
        return 3

    # Server returns Java LocalDateTime.toString() -> naive ISO-8601.
    # Compare naively against local clock; run this script on a host in
    # the same timezone as the backend.
    try:
        last = datetime.fromisoformat(raw)
    except ValueError as e:
        print(f"error: could not parse timestamp {raw!r}: {e}",
              file=sys.stderr)
        return 1

    now = datetime.now()
    age_minutes = (now - last).total_seconds() / 60.0

    if args.verbose:
        print(f"last checkin: {last.isoformat()}")
        print(f"age: {age_minutes:.1f} min "
              f"(threshold: {args.max_age_minutes} min)")

    if age_minutes > args.max_age_minutes:
        msg = (f"secman alert: CrowdStrike checkin is stale. "
               f"Last checkin {last.isoformat()} "
               f"({age_minutes:.0f} min ago, "
               f"threshold {args.max_age_minutes} min). "
               f"Source: {args.url}")
        try:
            send_telegram(args.telegram_bot_token, args.telegram_chat_id,
                          msg, args.timeout)
        except (urllib.error.URLError, TimeoutError, OSError,
                RuntimeError) as e:
            print(f"error: telegram send failed: {e}", file=sys.stderr)
            return 1
        print(msg)
        return 3

    if args.verbose:
        print("OK: checkin is fresh")
    return 0


if __name__ == "__main__":
    sys.exit(main())
