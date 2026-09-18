"""Reconcile existing SecMan relationships through the importer's REST client."""

from collections import defaultdict
from dataclasses import dataclass, field
import json
import logging
import os
import re
import sys
from urllib.parse import urlsplit

import requests

log = logging.getLogger("adread.sync")
BATCH_SIZE = 500
EMAIL_PATTERN = re.compile(r"[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?(?:\.[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?)+")
UUID_PATTERN = re.compile(r"[0-9A-Fa-f]{8}(?:-[0-9A-Fa-f]{4}){3}-[0-9A-Fa-f]{12}")


def normalize_email(value: object) -> str | None:
  if not isinstance(value, str):
    return None
  email = value.strip().lower()
  if len(email) > 254 or not EMAIL_PATTERN.fullmatch(email):
    return None
  local, domain = email.split("@", 1)
  if (len(local) > 64 or local.startswith(".") or local.endswith(".")
      or ".." in local or any(len(label) > 63 for label in domain.split("."))):
    return None
  return email


def account_id(value: object) -> str | None:
  if isinstance(value, str) and re.fullmatch(r"[0-9]{12}", value.strip()):
    return value.strip()
  return None


def is_non_aws_cloud_account(value: object) -> bool:
  return isinstance(value, str) and UUID_PATTERN.fullmatch(value.strip()) is not None


def record_id(record: object) -> int | None:
  value = record.get("id") if isinstance(record, dict) else None
  return value if type(value) is int and value > 0 else None


def workgroup_is_enabled(record: object) -> bool:
  """Treat old responses without the field as enabled; reject other non-booleans later."""
  return isinstance(record, dict) and record.get("enabled", True) is True


def http_status(error: Exception) -> int | None:
  if isinstance(error, requests.HTTPError) and error.response is not None:
    return error.response.status_code
  return None


@dataclass
class SyncPlan:
  additions: dict[int, list[int]] = field(default_factory=dict)
  removals: dict[int, list[int]] = field(default_factory=dict)
  workgroups_evaluated: int = 0
  owners_evaluated: int = 0
  unique_owner_email_addresses: int = 0
  workgroups_without_owner: int = 0
  aws_accounts_matched: int = 0
  assets_matched: int = 0
  relationships_to_add: int = 0
  relationships_to_remove: int = 0
  relationships_added: int = 0
  relationships_removed: int = 0
  skipped_accounts: int = 0
  errors: int = 0

  def invalid(self, kind: str, identifier: object) -> None:
    self.errors += 1
    log.warning("Skipping invalid %s record id=%r", kind, identifier)

  def summary(self, dry_run: bool) -> dict:
    return {"dry_run": dry_run, **{
      key: value for key, value in vars(self).items() if key not in ("additions", "removals")
    }}


def index_owners(workgroups: list, plan: SyncPlan) -> dict[str, set[int]]:
  by_email = defaultdict(set)
  for group in workgroups:
    gid = record_id(group)
    if gid is None:
      plan.invalid("workgroup", None)
      continue
    enabled = group.get("enabled", True)
    if type(enabled) is not bool:
      plan.invalid("workgroup enabled status", gid)
      continue
    if not enabled:
      log.info("Skipping disabled workgroup_id=%d", gid)
      continue
    plan.workgroups_evaluated += 1
    raw_owner = group.get("ownerEmail")
    if raw_owner is None or raw_owner == "":
      plan.workgroups_without_owner += 1
      log.info("Skipping workgroup_id=%d: no canonical owner email", gid)
      continue
    email = normalize_email(raw_owner)
    if email is None:
      plan.invalid("workgroup owner", gid)
      continue
    plan.owners_evaluated += 1
    by_email[email].add(gid)
  plan.unique_owner_email_addresses = len(by_email)
  return by_email


def index_accounts(mappings: list, by_email: dict[str, set[int]], plan: SyncPlan) -> dict[str, set[int]]:
  by_account = defaultdict(set)
  for mapping in mappings:
    if not isinstance(mapping, dict):
      plan.invalid("mapping", None)
      continue
    raw_account = mapping.get("awsAccountId")
    if raw_account is None or raw_account == "":
      continue  # Domain/IP-only mappings are unrelated to AWS ownership.
    account = account_id(raw_account)
    if account is None:
      plan.invalid("mapping AWS account", record_id(mapping))
      continue
    groups = by_account[account]
    email = normalize_email(mapping.get("email"))
    if email is None:
      log.warning("AWS account=%s mapping_id=%r has no valid owner email", account, record_id(mapping))
    else:
      groups.update(by_email.get(email, set()))
  return by_account


def build_plan(workgroups: list, mappings: list, assets: list) -> SyncPlan:
  plan = SyncPlan()
  owners = index_owners(workgroups, plan)
  touched_workgroups = {gid for group_ids in owners.values() for gid in group_ids}
  by_account = index_accounts(mappings, owners, plan)
  additions = defaultdict(set)
  removals = defaultdict(set)
  matched_assets = set()
  for asset in assets:
    aid = record_id(asset)
    if aid is None:
      plan.invalid("asset", aid)
      continue
    groups = asset.get("workgroups")
    if not isinstance(groups, list) or any(record_id(group) is None for group in groups):
      plan.invalid("asset workgroups", aid)
      continue  # A missing collection is not evidence of zero assignments.
    existing = {record_id(group) for group in groups}
    for gid in existing & touched_workgroups:
      removals[gid].add(aid)
    raw_account = asset.get("cloudAccountId")
    if raw_account is None or raw_account == "":
      continue
    account = account_id(raw_account)
    if account is None:
      if is_non_aws_cloud_account(raw_account):
        continue
      plan.invalid("asset AWS account", aid)
      continue
    desired = by_account.setdefault(account, set())
    if not desired:
      continue
    matched_assets.add(aid)
    for gid in desired:
      additions[gid].add(aid)
  plan.additions = {gid: sorted(ids) for gid, ids in sorted(additions.items())}
  plan.removals = {gid: sorted(ids) for gid, ids in sorted(removals.items())}
  plan.assets_matched = len(matched_assets)
  plan.aws_accounts_matched = sum(bool(groups) for groups in by_account.values())
  skipped = sorted(account for account, groups in by_account.items() if not groups)
  plan.skipped_accounts = len(skipped)
  for account in skipped:
    log.info("Skipping AWS account=%s: no mapping email matching a workgroup owner", account)
  plan.relationships_to_add = sum(len(ids) for ids in plan.additions.values())
  plan.relationships_to_remove = sum(len(ids) for ids in plan.removals.values())
  return plan


def load_mappings(client) -> list:
  mappings = []
  # These two DB-paged endpoints partition UserMapping. Applied rows still grant
  # AWS access, so reading /current alone silently loses existing owners.
  for path in ("/api/user-mappings/current", "/api/user-mappings/applied-history"):
    page = 0
    total_pages = None
    while total_pages is None or page < total_pages:
      data = client.get_json(path, {"page": page, "size": BATCH_SIZE})
      if (not isinstance(data, dict) or not isinstance(data.get("content"), list)
          or type(data.get("totalPages")) is not int or data["totalPages"] < 0
          or data.get("page") != page):
        raise RuntimeError("Invalid mapping page; synchronization aborted before writes")
      if total_pages is not None and data["totalPages"] != total_pages:
        raise RuntimeError("Mapping page count changed; retry synchronization after imports finish")
      total_pages = data["totalPages"]
      if page < total_pages and not data["content"]:
        raise RuntimeError("Incomplete mapping page; synchronization aborted before writes")
      mappings.extend(data["content"])
      page += 1
  return mappings


def hydrate_asset_workgroups(client, workgroups: list, assets: list) -> None:
  """Recover omitted memberships without treating an incomplete response as empty."""
  if all(not isinstance(asset, dict) or isinstance(asset.get("workgroups"), list)
         for asset in assets):
    return

  assets_by_id = {
    aid: asset for asset in assets
    if isinstance(asset, dict) and (aid := record_id(asset)) is not None
  }
  memberships = defaultdict(list)
  for group in workgroups:
    gid = record_id(group)
    count = group.get("assetCount") if isinstance(group, dict) else None
    if gid is None or type(count) is not int or count < 0:
      raise RuntimeError("Invalid workgroup asset count; synchronization aborted before writes")
    if count == 0:
      continue
    assigned = client.get_json(f"/api/workgroups/{gid}/assets")
    if not isinstance(assigned, list) or len(assigned) != count:
      raise RuntimeError("Incomplete workgroup asset membership; synchronization aborted before writes")
    for asset_ref in assigned:
      aid = record_id(asset_ref)
      if aid is None or aid not in assets_by_id:
        raise RuntimeError("Invalid workgroup asset membership; synchronization aborted before writes")
      memberships[aid].append({"id": gid})

  for aid, asset in assets_by_id.items():
    if asset.get("workgroups") is None:
      asset["workgroups"] = memberships.get(aid, [])


def synchronize(client, dry_run: bool) -> SyncPlan:
  workgroups = client.get_json("/api/workgroups")
  mappings = load_mappings(client)
  assets = client.get_json("/api/assets")
  if not all(isinstance(rows, list) for rows in (workgroups, assets)):
    raise RuntimeError("Invalid collection response; synchronization aborted before writes")
  hydrate_asset_workgroups(client, [group for group in workgroups if workgroup_is_enabled(group)], assets)
  plan = build_plan(workgroups, mappings, assets)
  touched_ids = sorted(set(plan.removals) | set(plan.additions))
  for gid in touched_ids:
    remove_ids = plan.removals.get(gid, [])
    add_ids = plan.additions.get(gid, [])
    log.info(
      "workgroup_id=%d relationships_to_remove=%d relationships_to_add=%d dry_run=%s",
      gid, len(remove_ids), len(add_ids), dry_run,
    )
    if dry_run:
      continue
    removal_failed = False
    for offset in range(0, len(remove_ids), BATCH_SIZE):
      batch = remove_ids[offset:offset + BATCH_SIZE]
      try:
        client.remove_assets(gid, batch)
      except (requests.RequestException, RuntimeError) as exc:
        plan.errors += 1
        removal_failed = True
        log.error(
          "workgroup_id=%d asset_ids=%s operation=remove outcome=unconfirmed "
          "error_type=%s http_status=%s; additions skipped; rerun to reconcile",
          gid, batch, type(exc).__name__, http_status(exc),
        )
        break
      plan.relationships_removed += len(batch)
      log.info("workgroup_id=%d asset_ids=%s outcome=removed", gid, batch)
    if removal_failed:
      continue
    for offset in range(0, len(add_ids), BATCH_SIZE):
      batch = add_ids[offset:offset + BATCH_SIZE]
      try:
        client.assign_assets(gid, batch)
      except (requests.RequestException, RuntimeError) as exc:
        plan.errors += 1
        log.error("workgroup_id=%d asset_ids=%s outcome=unconfirmed error_type=%s http_status=%s; rerun to reconcile",
                  gid, batch, type(exc).__name__, http_status(exc))
        break  # Keep completed batches; other workgroups can still be processed.
      plan.relationships_added += len(batch)
      log.info("workgroup_id=%d asset_ids=%s outcome=assigned", gid, batch)
  return plan


def run_command(client_factory, dry_run: bool) -> int:
  required = ("SECMAN_BACKEND_URL", "SECMAN_ADMIN_NAME", "SECMAN_ADMIN_PASS")
  missing = [name for name in required if not os.environ.get(name)]
  if missing:
    log.error("Missing configuration: %s", ", ".join(missing))
    return 1
  client = None
  try:
    url = os.environ["SECMAN_BACKEND_URL"]
    parsed = urlsplit(url)
    if (parsed.scheme != "https" or not parsed.hostname or parsed.username is not None
        or parsed.password is not None or parsed.query or parsed.fragment):
      raise ValueError("Invalid backend URL")
    client = client_factory(url, os.environ["SECMAN_ADMIN_NAME"], os.environ["SECMAN_ADMIN_PASS"],
                            dry_run=dry_run, verify_tls=True)
    client.login(require_admin=True)
    plan = synchronize(client, dry_run)
    print(json.dumps(plan.summary(dry_run), sort_keys=True))
    log.info("actor=%r operation=SYNC_WORKGROUP_ASSETS outcome=%s errors=%d",
             os.environ["SECMAN_ADMIN_NAME"], "dry-run" if dry_run else "finished", plan.errors)
    return 1 if plan.errors else 0
  except (requests.RequestException, RuntimeError, ValueError) as exc:
    log.error("Synchronization failed error_type=%s http_status=%s; check HTTPS URL, CA trust, ADMIN credentials and API availability",
              type(exc).__name__, http_status(exc))
    print(json.dumps({"dry_run": dry_run, "errors": 1, "aborted": True}), file=sys.stdout)
    return 1
  finally:
    if client is not None:
      client._session.close()
