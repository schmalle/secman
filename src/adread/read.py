import argparse
import logging
import os
import sys
import time

import requests

from azure.identity import ClientSecretCredential

TENANT_ID = os.environ["AZURE_TENANT_ID"]
CLIENT_ID = os.environ["AZURE_CLIENT_ID"]
CLIENT_SECRET = os.environ["AZURE_CLIENT_SECRET"]

GRAPH_BASE = "https://graph.microsoft.com/v1.0"

# (connect, read) timeouts for every HTTP call so a stalled socket can never hang
# the run indefinitely.
HTTP_TIMEOUT = (10, 60)

# Throttle/transient-error retry policy for Graph GETs. Graph throttles aggressively
# (HTTP 429) and occasionally returns 503/504; we honor Retry-After when present and
# otherwise back off exponentially.
RETRY_STATUS = (429, 503, 504)
MAX_RETRIES = 5
BACKOFF_BASE_SECONDS = 2
BACKOFF_CAP_SECONDS = 60

# Re-acquire the Graph token when it is within this many seconds of expiry. A full
# 2128-group run can outlast a single token's ~60-75 min lifetime.
TOKEN_REFRESH_SKEW_SECONDS = 300

# Logging is configured from the environment so verbosity can be tuned without
# code changes. LOG_LEVEL accepts the standard names (DEBUG, INFO, WARNING, ...);
# set it to DEBUG to see full request/response detail.
logging.basicConfig(
    level=os.environ.get("LOG_LEVEL", "INFO").upper(),
    format="%(asctime)s %(levelname)-8s %(name)s: %(message)s",
)
log = logging.getLogger("adread")


class GraphTokenProvider:
  """Acquires and transparently refreshes the app-only Graph access token.

  The client-credentials flow only honors *Application* permissions, so the
  service principal must hold User.Read.All (Application) for member mail /
  userPrincipalName to be readable — otherwise Graph returns them as null.
  """

  def __init__(self):
    self._credential = ClientSecretCredential(
      tenant_id=TENANT_ID,
      client_id=CLIENT_ID,
      client_secret=CLIENT_SECRET,
    )
    self._token = None
    self._expires_on = 0

  def _refresh(self):
    log.info("Acquiring access token (tenant=%s, client=%s)", TENANT_ID, CLIENT_ID)
    start = time.monotonic()
    token = self._credential.get_token("https://graph.microsoft.com/.default")
    elapsed = time.monotonic() - start

    self._token = token.token
    self._expires_on = token.expires_on
    log.info(
      "Access token acquired in %.2fs (len=%d, expires in ~%.0fs)",
      elapsed,
      len(self._token),
      self._expires_on - time.time(),
    )
    log.debug("Token expires_on (epoch): %s", self._expires_on)

  def token(self):
    """Return a valid token, refreshing if missing or near expiry."""
    if self._token is None or self._expires_on - time.time() < TOKEN_REFRESH_SKEW_SECONDS:
      self._refresh()
    return self._token

  def headers(self):
    return {
      "Authorization": f"Bearer {self.token()}",
      "Content-Type": "application/json",
    }


def _backoff_seconds(attempt):
  """Exponential backoff capped at BACKOFF_CAP_SECONDS (attempt starts at 1)."""
  return min(BACKOFF_BASE_SECONDS * (2 ** (attempt - 1)), BACKOFF_CAP_SECONDS)


def _graph_get(session, url, token_provider):
  """Single Graph GET with throttle/transient-error retries; returns parsed JSON."""
  for attempt in range(1, MAX_RETRIES + 1):
    headers = token_provider.headers()
    start = time.monotonic()
    try:
      response = session.get(url, headers=headers, timeout=HTTP_TIMEOUT)
    except requests.exceptions.RequestException as exc:
      if attempt == MAX_RETRIES:
        log.error("Request error for %s after %d attempts: %s", url, attempt, exc)
        raise
      sleep = _backoff_seconds(attempt)
      log.warning(
        "Request error for %s (%s) — retrying in %.1fs (attempt %d/%d)",
        url, exc, sleep, attempt, MAX_RETRIES,
      )
      time.sleep(sleep)
      continue

    elapsed = time.monotonic() - start
    log.debug(
      "HTTP %d in %.2fs (%d bytes) for %s",
      response.status_code, elapsed, len(response.content), url,
    )

    if response.status_code in RETRY_STATUS:
      if attempt == MAX_RETRIES:
        log.error("Giving up after %d attempts: HTTP %d for %s", attempt, response.status_code, url)
        response.raise_for_status()
      retry_after = response.headers.get("Retry-After")
      sleep = float(retry_after) if retry_after else _backoff_seconds(attempt)
      log.warning(
        "Throttled/transient HTTP %d for %s — sleeping %.1fs (attempt %d/%d)",
        response.status_code, url, sleep, attempt, MAX_RETRIES,
      )
      time.sleep(sleep)
      continue

    if not response.ok:
      log.error(
        "Request failed: HTTP %d for %s\nBody: %s",
        response.status_code, url, response.text,
      )
      response.raise_for_status()

    return response.json()


def graph_get_all(session, url, token_provider):
  """Read all pages from a Microsoft Graph collection endpoint."""
  items = []
  page = 0
  log.info("Starting paged GET: %s", url)

  while url:
    page += 1
    log.debug("Fetching page %d: %s", page, url)
    data = _graph_get(session, url, token_provider)

    page_items = data.get("value", [])
    items.extend(page_items)
    log.info(
      "Page %d: %d items (running total %d)",
      page,
      len(page_items),
      len(items),
    )
    log.debug("Page %d raw payload: %s", page, data)

    url = data.get("@odata.nextLink")
    if url:
      log.debug("Following @odata.nextLink for page %d", page + 1)

  log.info("Finished paged GET: %d pages, %d items total", page, len(items))
  return items


class SecmanClient:
  """Thin HTTP client for secman workgroup import."""

  MAX_WORKGROUP_NAME_LEN = 100

  def __init__(self, backend_url, admin_name, admin_pass, dry_run=False, verify_tls=True):
    self.base = backend_url.rstrip("/")
    self._admin_name = admin_name
    self._admin_pass = admin_pass
    self.dry_run = dry_run
    self._session = requests.Session()
    self._session.headers.update({"Content-Type": "application/json"})
    # Allow connecting to backends presenting a self-signed certificate. When
    # disabled we also silence urllib3's per-request InsecureRequestWarning so
    # the log isn't flooded across the run.
    self._session.verify = verify_tls
    if not verify_tls:
      requests.packages.urllib3.disable_warnings(
        requests.packages.urllib3.exceptions.InsecureRequestWarning
      )
      log.warning("SECMAN TLS verification DISABLED (accepting self-signed certificates)")
    # Cache of lowercased name → id populated on first ensure_workgroup miss.
    self._workgroup_cache = None

  def login(self):
    log.info("SECMAN login (user=%s, url=%s)", self._admin_name, self.base)
    resp = self._session.post(
      f"{self.base}/api/auth/login",
      json={"username": self._admin_name, "password": self._admin_pass},
      timeout=HTTP_TIMEOUT,
    )
    if not resp.ok:
      log.error("SECMAN login failed: HTTP %d — %s", resp.status_code, resp.text)
      resp.raise_for_status()

    data = resp.json()

    # Extract JWT from the secman_auth cookie and use it as a Bearer header
    # so it works over plain HTTP (no Secure-cookie restriction).
    token = self._session.cookies.get("secman_auth")
    if not token:
      raise RuntimeError("Login succeeded but secman_auth cookie missing in response")
    self._session.headers["Authorization"] = f"Bearer {token}"
    log.debug("SECMAN Bearer token set (len=%d)", len(token))

    roles = data.get("roles", [])
    if "ADMIN" not in roles:
      log.warning(
        "SECMAN user '%s' does not have the ADMIN role (roles=%s). "
        "Cross-domain user lazy-create will be restricted; import may be incomplete.",
        self._admin_name,
        roles,
      )
    else:
      log.info("SECMAN login OK — user has ADMIN role")

  def _load_workgroup_cache(self):
    resp = self._session.get(f"{self.base}/api/workgroups", timeout=HTTP_TIMEOUT)
    resp.raise_for_status()
    self._workgroup_cache = {wg["name"].lower(): wg["id"] for wg in resp.json()}
    log.debug("Loaded %d existing workgroups into cache", len(self._workgroup_cache))

  def ensure_workgroup(self, name):
    """Return the secman workgroup id for *name*, creating it if absent."""
    if len(name) > self.MAX_WORKGROUP_NAME_LEN:
      raise ValueError(
        f"AD group name '{name}' exceeds the {self.MAX_WORKGROUP_NAME_LEN}-char "
        "workgroup name limit — skipping"
      )

    if self.dry_run:
      log.info("[DRY-RUN] Would ensure workgroup '%s'", name)
      return None

    resp = self._session.post(
      f"{self.base}/api/workgroups",
      json={"name": name, "description": f"Imported from AD group '{name}'"},
      timeout=HTTP_TIMEOUT,
    )

    if resp.status_code == 201:
      wg_id = resp.json()["id"]
      log.info("AUDIT: operation=CREATE_WORKGROUP, name='%s', id=%s", name, wg_id)
      if self._workgroup_cache is not None:
        self._workgroup_cache[name.lower()] = wg_id
      return wg_id

    # Name already taken — resolve via the list endpoint (cached).
    if resp.status_code in (400, 409):
      if self._workgroup_cache is None:
        self._load_workgroup_cache()
      wg_id = self._workgroup_cache.get(name.lower())
      if wg_id is not None:
        log.info("Workgroup '%s' already exists with id=%s", name, wg_id)
        return wg_id
      log.error(
        "Workgroup '%s' returned %d on create but was not found in the list",
        name,
        resp.status_code,
      )
      resp.raise_for_status()

    log.error(
      "Unexpected response creating workgroup '%s': HTTP %d — %s",
      name,
      resp.status_code,
      resp.text,
    )
    resp.raise_for_status()

  def add_members(self, workgroup_id, emails):
    """Add *emails* to workgroup *workgroup_id* (additive, idempotent)."""
    if not emails:
      log.debug("No members to add for workgroup id=%s", workgroup_id)
      return

    if self.dry_run:
      log.info(
        "[DRY-RUN] Would add %d member(s) to workgroup id=%s: %s",
        len(emails),
        workgroup_id,
        emails,
      )
      return

    user_refs = [{"email": e} for e in emails]
    resp = self._session.post(
      f"{self.base}/api/workgroups/{workgroup_id}/users",
      json={"userRefs": user_refs},
      timeout=HTTP_TIMEOUT,
    )
    if not resp.ok:
      log.error(
        "Failed to add members to workgroup id=%s: HTTP %d — %s",
        workgroup_id,
        resp.status_code,
        resp.text,
      )
      resp.raise_for_status()

    log.info(
      "AUDIT: operation=ADD_MEMBERS, workgroup_id=%s, count=%d",
      workgroup_id,
      len(emails),
    )


def main():
  parser = argparse.ArgumentParser(
    description="Read AWS-prefixed Azure AD groups and optionally import them as secman workgroups.",
  )
  parser.add_argument(
    "--import",
    dest="do_import",
    action="store_true",
    help="Create secman workgroups and add members for each AD group found. "
         "Requires SECMAN_BACKEND_URL, SECMAN_ADMIN_NAME, SECMAN_ADMIN_PASS.",
  )
  parser.add_argument(
    "--dry-run",
    dest="dry_run",
    action="store_true",
    help="With --import: log what would be created/assigned without writing anything.",
  )
  parser.add_argument(
    "--insecure",
    dest="insecure",
    action="store_true",
    default=os.environ.get("SECMAN_INSECURE", "").lower() in ("1", "true", "yes"),
    help="Skip TLS certificate verification for the SECMAN backend "
         "(accept self-signed certificates). May also be set via SECMAN_INSECURE=1.",
  )
  args = parser.parse_args()

  if args.dry_run and not args.do_import:
    parser.error("--dry-run requires --import")

  secman = None
  if args.do_import:
    missing = [v for v in ("SECMAN_BACKEND_URL", "SECMAN_ADMIN_NAME", "SECMAN_ADMIN_PASS") if not os.environ.get(v)]
    if missing:
      print(f"ERROR: --import requires these env vars to be set: {', '.join(missing)}", file=sys.stderr)
      sys.exit(1)
    secman = SecmanClient(
      backend_url=os.environ["SECMAN_BACKEND_URL"],
      admin_name=os.environ["SECMAN_ADMIN_NAME"],
      admin_pass=os.environ["SECMAN_ADMIN_PASS"],
      dry_run=args.dry_run,
      verify_tls=not args.insecure,
    )
    if not args.dry_run:
      secman.login()

  log.info("Starting adread run")
  overall_start = time.monotonic()

  token_provider = GraphTokenProvider()
  graph_session = requests.Session()

  # Only fetch groups whose displayName starts with "AWS-". Graph's startswith()
  # is case-sensitive, so OR together the common casings to approximate a
  # case-insensitive match server-side.
  prefix_filter = " or ".join(
    f"startswith(displayName,'{p}')" for p in ("AWS-", "aws-", "Aws-")
  )
  groups_url = (
    f"{GRAPH_BASE}/groups"
    "?$select=id,displayName,mail"
    f"&$filter={requests.utils.quote(prefix_filter)}"
  )

  groups = graph_get_all(graph_session, groups_url, token_provider)
  log.info("Retrieved %d group(s) with leading 'AWS-'", len(groups))

  total_members = 0
  failed_groups = []

  for index, group in enumerate(groups, start=1):
    group_id = group["id"]
    group_name = group.get("displayName")
    group_mail = group.get("mail")
    log.info(
      "Processing group %d/%d: %s (%s) id=%s",
      index,
      len(groups),
      group_name,
      group_mail,
      group_id,
    )

    members_url = (
      f"{GRAPH_BASE}/groups/{group_id}/members/microsoft.graph.user"
      "?$select=id,mail,userPrincipalName"
      "&$top=999"
    )

    try:
      users = graph_get_all(graph_session, members_url, token_provider)
    except Exception as exc:
      log.error("FAILED to read members of group '%s': %s — skipping", group_name, exc)
      failed_groups.append(group_name)
      continue

    total_members += len(users)
    log.info("Group %s has %d user member(s)", group_name, len(users))

    print(f"\nGroup: {group_name} ({group_mail})")

    emails = []
    logged_empty_sample = False
    for user in users:
      email = user.get("mail") or user.get("userPrincipalName")
      log.debug("Member of %s: id=%s email=%s", group_name, user.get("id"), email)
      print(f" {email}")
      if email:
        emails.append(email)
      elif not logged_empty_sample:
        # Both mail and userPrincipalName null — almost always means the app-only
        # token lacks User.Read.All (Application). Log one raw member per group so
        # the permission state is observable without flooding the output.
        log.warning(
          "Member of '%s' has no mail/userPrincipalName (check User.Read.All "
          "Application permission). Raw object: %s",
          group_name, user,
        )
        logged_empty_sample = True

    if secman is not None:
      try:
        wg_id = secman.ensure_workgroup(group_name)
        secman.add_members(wg_id, emails)
      except ValueError as exc:
        log.warning("SKIP group '%s': %s", group_name, exc)
        failed_groups.append(group_name)
      except Exception as exc:
        log.error("FAILED to import group '%s': %s", group_name, exc)
        failed_groups.append(group_name)

  elapsed = time.monotonic() - overall_start
  log.info(
    "Run complete in %.2fs: %d groups, %d total user members",
    elapsed,
    len(groups),
    total_members,
  )

  if failed_groups:
    log.error("Import finished with %d failed group(s): %s", len(failed_groups), failed_groups)
    sys.exit(1)


if __name__ == "__main__":
  try:
    main()
  except KeyboardInterrupt:
    log.warning("Interrupted by user (Ctrl-C) — exiting")
    sys.exit(130)
