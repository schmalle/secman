# clinotify

Lightweight, stdlib-only Python scripts that monitor secman from the outside
and raise alerts on notification channels (currently Telegram).

These are intentionally separate from the main Kotlin `src/cli/` tool — they
need **no build step**, **no JVM**, and **no credentials** against secman,
because they only consume the public CrowdStrike-checkin endpoint.

## Scripts

### `check_crowdstrike_checkin.py`

Polls `GET /api/crowdstrike/last-checkin` (a public, unauthenticated
endpoint) and sends a Telegram message when the last CrowdStrike import is
older than a configurable threshold — or when the endpoint returns the
literal string `never`.

#### Requirements

* Python 3.9+ (uses `datetime.fromisoformat`)
* Stdlib only — no `pip install` needed
* Network access to both the secman host and `api.telegram.org`
* A Telegram bot token and the target chat id

#### Telegram setup

1. Create a bot with [@BotFather](https://t.me/BotFather) and copy the token
   it returns (looks like `123456789:ABC-DEF...`).
2. Start a chat with your bot (or add it to a group) and send any message.
3. Get the chat id:
   ```
   curl -s "https://api.telegram.org/bot<TOKEN>/getUpdates" \
       | python3 -c 'import json,sys; print(json.load(sys.stdin))'
   ```
   Look for `"chat":{"id": ...}`. For group chats the id is negative.

#### Usage

```
check_crowdstrike_checkin.py --url URL --max-age-minutes N
                             [--telegram-bot-token TOKEN]
                             [--telegram-chat-id CHAT_ID]
                             [--insecure] [--timeout SECS] [--verbose]
```

| Flag                    | Description                                                   |
|-------------------------|---------------------------------------------------------------|
| `--url`                 | Base URL of the secman instance (e.g. `https://secman.example.com`). |
| `--max-age-minutes N`   | Alert if the last checkin is older than N minutes or is `never`. |
| `--telegram-bot-token`  | Bot token. Defaults to `$TELEGRAM_BOT_TOKEN`.                 |
| `--telegram-chat-id`    | Chat id. Defaults to `$TELEGRAM_CHAT_ID`.                     |
| `--insecure`            | Disable TLS verification on the secman call (self-signed certs). |
| `--timeout`             | HTTP timeout in seconds (default: `15`).                      |
| `--verbose` / `-v`      | Print diagnostic info to stdout.                              |

#### Exit codes

| Code | Meaning                                                |
|------|--------------------------------------------------------|
| `0`  | OK — last checkin is within the threshold              |
| `1`  | Transport / parse error (no alert sent)                |
| `2`  | Invalid arguments                                      |
| `3`  | Alert fired (checkin stale or `never`)                 |

#### Examples

One-shot run, credentials via env:
```bash
export TELEGRAM_BOT_TOKEN=123456789:ABC-...
export TELEGRAM_CHAT_ID=-1001234567890

./check_crowdstrike_checkin.py \
    --url https://secman.example.com \
    --max-age-minutes 120 \
    --verbose
```

Cron, alert if no import in the last 2 hours:
```cron
*/10 * * * * TELEGRAM_BOT_TOKEN=... TELEGRAM_CHAT_ID=... \
  /opt/secman/src/clinotify/check_crowdstrike_checkin.py \
  --url https://secman.example.com --max-age-minutes 120 \
  >> /var/log/secman-checkin.log 2>&1
```

systemd timer (`/etc/systemd/system/secman-checkin.service`):
```ini
[Service]
Type=oneshot
Environment=TELEGRAM_BOT_TOKEN=...
Environment=TELEGRAM_CHAT_ID=...
ExecStart=/opt/secman/src/clinotify/check_crowdstrike_checkin.py \
    --url https://secman.example.com --max-age-minutes 120
```

#### Sample alerts

Stale:
```
secman alert: CrowdStrike checkin is stale. Last checkin
2026-04-21T02:13:04.112 (318 min ago, threshold 120 min).
Source: https://secman.example.com
```

Never imported:
```
secman alert: CrowdStrike has NEVER checked in
(source: https://secman.example.com).
```

#### Caveats

* The backend returns a Java `LocalDateTime` — naive, no timezone. The script
  compares it against the local wallclock on whatever host runs the script,
  so run it in the **same timezone as the secman backend**, or accept the
  skew. If this matters, extend the backend to emit a UTC `Instant` instead.
* The script is idempotent but **not stateful**: every invocation that finds
  a stale checkin will send a Telegram message. Throttle with cron cadence,
  or add a state file if you need deduplication.
