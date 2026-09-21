"""Offline fixtures exercise the CLI against the real REST request/response shapes."""

from contextlib import redirect_stdout
from copy import deepcopy
import io
import json
import os
from pathlib import Path
import secrets
import sys
import unittest
from unittest.mock import Mock, patch
from urllib.parse import urlsplit

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src" / "adread"))
import read
from sync_workgroup_assets import BATCH_SIZE, build_plan, load_mappings, normalize_email, synchronize


def workgroup(gid, name, owner_email=None):
  return {"id": gid, "name": name, "ownerEmail": owner_email}


def mapping(account="000000000001", email="a@example.com"):
  return {"id": 1, "awsAccountId": account, "email": email}


def asset(aid=10, account="000000000001", groups=()):
  return {"id": aid, "name": f"System {aid}", "cloudAccountId": account,
          "workgroups": [{"id": gid} for gid in groups]}


class FixtureClient:
  def __init__(self, groups=None, mappings=None, assets=None):
    self.groups = [
      workgroup(1, "X", "a@example.com"),
      workgroup(2, "Y", "b@example.com"),
      workgroup(3, "Manual"),
    ] if groups is None else groups
    self.mappings = [mapping()] if mappings is None else mappings
    self.assets = [asset()] if assets is None else assets
    self.assigned_assets = {}
    for group in self.groups:
      if not isinstance(group, dict) or type(group.get("id")) is not int:
        continue
      assigned = [
        item["id"] for item in self.assets
        if isinstance(item, dict)
        and isinstance(item.get("workgroups"), list)
        and any(ref.get("id") == group["id"] for ref in item["workgroups"])
      ]
      self.assigned_assets[group["id"]] = assigned
      group["assetCount"] = len(assigned)
    self.reads = []
    self.writes = []
    self.removals = []
    self.synced_accounts = {}

  def get_json(self, path, params=None):
    self.reads.append((path, params))
    if path == "/api/workgroups":
      return deepcopy(self.groups)
    if path == "/api/assets":
      return deepcopy(self.assets)
    if path.startswith("/api/workgroups/") and path.endswith("/assets"):
      gid = int(path.split("/")[3])
      return [{"id": aid} for aid in self.assigned_assets.get(gid, [])]
    if path.endswith("/owner-sync"):
      gid = int(path.split("/")[3])
      group = next(group for group in self.groups if group["id"] == gid)
      desired = sorted({row["awsAccountId"] for row in self.mappings
                        if row.get("email") == group.get("ownerEmail")}) if group.get("enabled", True) else []
      current = self.synced_accounts.get(gid, [])
      return {"workgroupId": gid, "desiredAccounts": desired,
              "additions": sorted(set(desired) - set(current)), "removals": sorted(set(current) - set(desired))}
    rows = self.mappings if path.endswith("/current") else []
    page = params["page"]
    return {"content": deepcopy(rows[page * BATCH_SIZE:(page + 1) * BATCH_SIZE]),
            "totalPages": (len(rows) + BATCH_SIZE - 1) // BATCH_SIZE, "page": page}

  def reconcile_owner_accounts(self, gid, accounts):
    self.writes.append((gid, list(accounts)))
    self.synced_accounts[gid] = list(accounts)

  def assign_assets(self, gid, ids):
    self.writes.append((gid, list(ids)))
    for item in self.assets:
      if item["id"] in ids and {"id": gid} not in item["workgroups"]:
        item["workgroups"].append({"id": gid})

  def remove_assets(self, gid, ids):
    self.removals.append((gid, list(ids)))
    for item in self.assets:
      if item["id"] in ids:
        item["workgroups"] = [group for group in item["workgroups"] if group["id"] != gid]
    self.assigned_assets[gid] = [aid for aid in self.assigned_assets.get(gid, []) if aid not in ids]


class MatchingTests(unittest.TestCase):
  def test_aws_managed_group_is_not_repopulated_but_manual_group_is_unchanged(self):
    managed = {**workgroup(1, "aws-DevOps-test", "a@example.com"), "awsAccountManaged": True}
    manual = workgroup(2, "aws-manual", "a@example.com")
    plan = build_plan([managed, manual], [mapping()], [asset(groups=(1, 2))])
    self.assertEqual({2: [10]}, plan.additions)
    self.assertEqual({2: [10]}, plan.removals)

  def plan(self, **kwargs):
    client = FixtureClient(**kwargs)
    return build_plan(client.groups, client.mappings, client.assets)

  def test_basic_mapping(self):
    plan = self.plan()
    self.assertEqual({1: [10]}, plan.additions)
    self.assertEqual((3, 2, 2, 1, 1, 1, 1, 0, 0), (
      plan.workgroups_evaluated, plan.owners_evaluated, plan.unique_owner_email_addresses,
      plan.workgroups_without_owner,
      plan.aws_accounts_matched, plan.assets_matched, plan.relationships_to_add,
      plan.relationships_to_remove, plan.errors))

  def test_no_matching_account(self):
    self.assertEqual({}, self.plan(mappings=[]).additions)

  def test_multiple_accounts(self):
    plan = self.plan(mappings=[mapping(), mapping("000000000002")],
                     assets=[asset(), asset(20, "000000000002")])
    self.assertEqual({1: [10, 20]}, plan.additions)

  def test_multiple_owner_accounts(self):
    plan = self.plan(mappings=[mapping(), mapping("000000000002")],
                     assets=[asset(), asset(20, "000000000002")])
    self.assertEqual({1: [10, 20]}, plan.additions)

  def test_duplicate_ownership_and_records(self):
    plan = self.plan(mappings=[mapping(), mapping()],
                     assets=[asset(), asset()])
    self.assertEqual({1: [10]}, plan.additions)
    self.assertEqual(1, plan.assets_matched)

  def test_same_owner_in_multiple_workgroups(self):
    groups = [workgroup(1, "X", "a@example.com"), workgroup(2, "Y", "A@EXAMPLE.COM")]
    self.assertEqual({1: [10], 2: [10]}, self.plan(groups=groups).additions)

  def test_disabled_workgroups_are_ignored(self):
    client = FixtureClient(groups=[
      workgroup(1, "X", "a@example.com"),
      workgroup(2, "Y", "a@example.com"),
    ])
    client.groups[0]["enabled"] = False
    plan = build_plan(client.groups, client.mappings, client.assets)
    self.assertEqual({2: [10]}, plan.additions)
    self.assertEqual(1, plan.workgroups_evaluated)
    self.assertEqual(1, plan.owners_evaluated)

  def test_owner_of_only_disabled_workgroup_is_ignored(self):
    client = FixtureClient(groups=[workgroup(1, "X", "a@example.com")])
    client.groups[0]["enabled"] = False
    plan = build_plan(client.groups, client.mappings, client.assets)
    self.assertEqual({}, plan.additions)
    self.assertEqual(0, plan.owners_evaluated)
    self.assertEqual(0, plan.errors)

  def test_non_owner_member_mapping_does_not_assign_asset(self):
    plan = self.plan(mappings=[mapping(email="member@example.com")])
    self.assertEqual({}, plan.additions)

  def test_normalized_emails_and_account_whitespace(self):
    plan = self.plan(groups=[workgroup(1, "X", " A.User@Example.COM ")],
                     mappings=[mapping(" 000000000001 ", "a.user@example.com")])
    self.assertEqual({1: [10]}, plan.additions)

  def test_invalid_emails(self):
    for value in (None, "", " ", 42, {}, "no-at", "a@@example.com", "a@x", "a b@example.com",
                  "a@example.com\nforged", ".a@example.com", "a..b@example.com", "a@-example.com"):
      with self.subTest(value=value):
        self.assertIsNone(normalize_email(value))

  def test_missing_and_unknown_owners_are_valid_skips(self):
    for email in (None, "", "invalid", "unknown@example.com"):
      with self.subTest(email=email):
        plan = self.plan(mappings=[mapping(email=email)])
        self.assertEqual({}, plan.additions)
        self.assertEqual(1, plan.skipped_accounts)
        self.assertEqual(0, plan.errors)

  def test_workgroup_without_owner_is_skipped(self):
    plan = self.plan(groups=[workgroup(1, "X")])
    self.assertEqual({}, plan.additions)
    self.assertEqual(1, plan.workgroups_without_owner)
    self.assertEqual(0, plan.errors)

  def test_invalid_workgroup_owner_is_an_error(self):
    plan = self.plan(groups=[workgroup(1, "X", "invalid")])
    self.assertEqual({}, plan.additions)
    self.assertEqual(1, plan.errors)

  def test_accounts_on_assets_without_mapping_are_reported(self):
    plan = self.plan(mappings=[])
    self.assertEqual(1, plan.skipped_accounts)

  def test_assets_without_aws_and_domain_only_mappings_are_untouched(self):
    plan = self.plan(mappings=[mapping(None)], assets=[asset(account=None), asset(20, "")])
    self.assertEqual({}, plan.additions)
    self.assertEqual(0, plan.errors)

  def test_non_aws_uuid_cloud_accounts_are_ignored(self):
    plan = self.plan(assets=[asset(account="123e4567-e89b-12d3-a456-426614174000")])
    self.assertEqual({}, plan.additions)
    self.assertEqual(0, plan.errors)

  def test_owned_workgroup_is_cleared_while_ownerless_workgroup_is_preserved(self):
    client = FixtureClient(groups=[workgroup(2, "Y", "b@example.com"), workgroup(3, "Manual")], mappings=[], assets=[asset(groups=(2, 3))])
    before = deepcopy(client.assets)
    synchronize(client, False)
    self.assertEqual(before, client.assets)
    self.assertEqual([], client.removals)

  def test_repeat_run_replaces_existing_links(self):
    client = FixtureClient()
    self.assertEqual(1, synchronize(client, False).relationships_added)
    second = synchronize(client, False)
    self.assertEqual(0, second.relationships_added)
    self.assertEqual(0, second.relationships_removed)

  def test_dry_run_calculates_complete_plan_without_writes(self):
    client = FixtureClient(assets=[asset(groups=(1,))])
    before = deepcopy(client.assets)
    plan = synchronize(client, True)
    self.assertEqual(1, plan.relationships_to_add)
    self.assertEqual(0, plan.relationships_to_remove)
    self.assertEqual([], client.writes)
    self.assertEqual(before, client.assets)

  def test_malformed_records_do_not_stop_valid_records(self):
    plan = self.plan(groups=[None, workgroup(1, "X", "a@example.com"), workgroup(2, "Y", "invalid")],
                     mappings=[None, mapping(), mapping("bad")],
                     assets=[None, asset(), asset(20, "bad")])
    self.assertEqual({1: [10]}, plan.additions)
    self.assertEqual(6, plan.errors)

  def test_missing_asset_workgroups_is_not_treated_as_empty(self):
    broken = asset()
    broken["workgroups"] = None
    plan = self.plan(assets=[broken])
    self.assertEqual({}, plan.additions)
    self.assertEqual(1, plan.errors)

  def test_deterministic_sorted_plan(self):
    groups = [workgroup(2, "Y", "a@example.com"), workgroup(1, "X", "a@example.com")]
    plan = self.plan(groups=groups, assets=[asset(20), asset(10)])
    self.assertEqual([(1, [10, 20]), (2, [10, 20])], list(plan.additions.items()))


class TransportTests(unittest.TestCase):
  def test_canonical_owner_requires_one_valid_user_email(self):
    self.assertEqual(
      "owner@example.com",
      read.canonical_owner_email([{"mail": None, "userPrincipalName": " Owner@Example.COM "}]),
    )
    self.assertEqual(
      "owner@example.com",
      read.canonical_owner_email([{"mail": "invalid", "userPrincipalName": "owner@example.com"}]),
    )
    for owners in ([], [{"mail": "a@example.com"}, {"mail": "b@example.com"}], [{"mail": None}]):
      with self.subTest(owners=owners), self.assertRaises(ValueError):
        read.canonical_owner_email(owners)

  def test_graph_get_rejects_unexpected_host_and_redirects(self):
    session = Mock()
    token_provider = Mock()
    with self.assertRaisesRegex(ValueError, "unexpected Microsoft Graph URL"):
      read._graph_get(session, "https://example.com/v1.0/groups", token_provider)
    session.get.return_value = Mock(status_code=200, ok=True, content=b"{}", json=lambda: {})
    read._graph_get(session, "https://graph.microsoft.com/v1.0/groups", token_provider)
    self.assertFalse(session.get.call_args.kwargs["allow_redirects"])

  def test_existing_workgroup_owner_is_updated_without_logging_email(self):
    with patch.object(read.requests, "Session") as factory:
      session = factory.return_value
      create = Mock(status_code=409)
      listing = Mock(ok=True, json=lambda: [{"id": 7, "name": "AWS-X", "ownerEmail": None}])
      update = Mock(status_code=200)
      session.post.return_value = create
      session.get.return_value = listing
      session.put.return_value = update
      client = read.SecmanClient("https://secman.example", "operator", secrets.token_urlsafe())
      with self.assertLogs("adread", level="INFO") as logs:
        self.assertEqual(7, client.ensure_workgroup("AWS-X", "owner@example.com"))
      self.assertEqual({"ownerEmail": "owner@example.com"}, session.put.call_args.kwargs["json"])
      self.assertFalse(session.put.call_args.kwargs["allow_redirects"])
      self.assertNotIn("owner@example.com", " ".join(logs.output))

  def test_unresolved_ad_owner_preserves_existing_owner(self):
    with patch.object(read.requests, "Session") as factory:
      session = factory.return_value
      session.post.return_value = Mock(status_code=409)
      session.get.return_value = Mock(json=lambda: [{"id": 7, "name": "AWS-X", "ownerEmail": "owner@example.com"}])
      client = read.SecmanClient("https://secman.example", "operator", secrets.token_urlsafe())
      self.assertEqual(7, client.ensure_workgroup("AWS-X", None))
      session.put.assert_not_called()

  def test_disabled_workgroups_are_not_hydrated_or_written(self):
    client = FixtureClient()
    client.groups[0]["enabled"] = False
    client.synced_accounts[1] = ["000000000001"]
    plan = synchronize(client, False)
    self.assertEqual(1, plan.relationships_removed)
    self.assertEqual([], client.synced_accounts[1])
    self.assertNotIn(("/api/assets", None), client.reads)

  def test_omitted_asset_workgroups_are_loaded_from_counted_workgroups(self):
    client = FixtureClient()
    del client.assets[0]["workgroups"]
    plan = synchronize(client, True)
    self.assertEqual(1, plan.relationships_to_add)
    self.assertNotIn(("/api/assets", None), client.reads)

  def test_incomplete_workgroup_asset_read_aborts_before_writes(self):
    client = FixtureClient()
    client.groups[0]["assetCount"] = 99
    plan = synchronize(client, True)
    self.assertEqual(1, plan.relationships_to_add)
    self.assertEqual([], client.writes)

  def test_bulk_reads_and_bounded_assignment_batches(self):
    client = FixtureClient(assets=[asset(i) for i in range(1, 1002)])
    plan = synchronize(client, False)
    self.assertEqual(4, len(client.reads))
    self.assertEqual(1, plan.relationships_added)
    self.assertEqual(["000000000001"], client.synced_accounts[1])

  def test_removals_are_batched_before_reassignment(self):
    client = FixtureClient(assets=[asset(i, groups=(1,)) for i in range(1, 1002)])
    before = deepcopy(client.assets)
    synchronize(client, False)
    self.assertEqual([], client.removals)
    self.assertEqual(before, client.assets)

  def test_mapping_pagination_includes_applied_owners(self):
    client = FixtureClient(mappings=[mapping() for _ in range(BATCH_SIZE + 1)])
    original = client.get_json
    def get_json(path, params=None):
      if path.endswith("applied-history"):
        return {"content": [mapping(email="applied@example.com")], "totalPages": 1, "page": 0}
      return original(path, params)
    client.get_json = get_json
    result = load_mappings(client)
    self.assertEqual(BATCH_SIZE + 2, len(result))
    self.assertEqual("applied@example.com", result[-1]["email"])

  def test_failed_or_incomplete_read_prevents_all_writes(self):
    for response in ({"content": [], "totalPages": 2, "page": 0}, {}, None):
      with self.subTest(response=response):
        client = FixtureClient()
        client.get_json = Mock(return_value=response)
        with self.assertRaises(RuntimeError):
          synchronize(client, False)
        self.assertEqual([], client.writes)
        self.assertEqual([], client.removals)

  def test_partial_assignment_failure_keeps_other_workgroups_and_reports_error(self):
    client = FixtureClient()
    def fail(gid, accounts):
      raise RuntimeError("Mapping changed; review new preview")
    client.reconcile_owner_accounts = fail
    with self.assertRaisesRegex(RuntimeError, "Mapping changed"):
      synchronize(client, False)
    self.assertEqual([], client.writes)

  def test_removal_failure_skips_reassignment_for_that_workgroup(self):
    client = FixtureClient()
    original = client.get_json
    def malformed(path, params=None):
      if path.endswith("/2/aws-accounts/owner-sync"):
        return {"workgroupId": 2, "desiredAccounts": ["bad"], "additions": [], "removals": []}
      return original(path, params)
    client.get_json = malformed
    with self.assertRaisesRegex(RuntimeError, "Invalid owner-sync preview"):
      synchronize(client, False)
    self.assertEqual([], client.writes)

  def test_client_uses_existing_bulk_contract_and_never_follows_redirects(self):
    with patch.object(read.requests, "Session") as factory:
      session = factory.return_value
      session.get.return_value.status_code = 200
      session.post.return_value.status_code = 200
      session.delete.return_value.status_code = 204
      client = read.SecmanClient("https://secman.example", "operator", secrets.token_urlsafe())
      client.get_json("/api/workgroups")
      client.assign_assets(1, [10, 20])
      client.remove_assets(1, [10, 20])
      self.assertTrue(session.verify)
      self.assertFalse(session.get.call_args.kwargs["allow_redirects"])
      self.assertFalse(session.post.call_args.kwargs["allow_redirects"])
      self.assertFalse(session.delete.call_args.kwargs["allow_redirects"])
      self.assertEqual({"assetIds": [10, 20]}, session.post.call_args.kwargs["json"])
      self.assertEqual({"assetIds": [10, 20]}, session.delete.call_args.kwargs["json"])
      self.assertEqual("https://secman.example/api/workgroups/1/assets", session.post.call_args.args[0])
      self.assertEqual("https://secman.example/api/workgroups/1/assets", session.delete.call_args.args[0])

  def test_login_requires_admin_and_redacts_error_response(self):
    with patch.object(read.requests, "Session") as factory:
      session = factory.return_value
      response = session.post.return_value
      response.ok, response.is_redirect = True, False
      response.json.return_value = {"roles": ["USER"]}
      session.cookies.get.return_value = secrets.token_urlsafe()
      client = read.SecmanClient("https://secman.example", "operator", secrets.token_urlsafe())
      with self.assertRaisesRegex(RuntimeError, "ADMIN"):
        client.login(require_admin=True)
      response.ok, response.status_code = False, 401
      with self.assertRaisesRegex(read.requests.HTTPError, "HTTP 401"):
        client.login(require_admin=True)
      self.assertFalse(session.post.call_args.kwargs["allow_redirects"])

  def test_dry_run_client_refuses_writes(self):
    client = read.SecmanClient("https://secman.example", "operator", secrets.token_urlsafe(), dry_run=True)
    with self.assertRaises(RuntimeError):
      client.assign_assets(1, [10])
    with self.assertRaises(RuntimeError):
      client.remove_assets(1, [10])
    client._session.close()


class CommandTests(unittest.TestCase):
  def test_ad_import_continues_when_owner_lookup_fails(self):
    groups = [{"id": "123e4567-e89b-12d3-a456-426614174000", "displayName": "AWS-X"}]
    config = {"AZURE_TENANT_ID": "tenant", "AZURE_CLIENT_ID": "client",
              "AZURE_CLIENT_SECRET": secrets.token_urlsafe(),
              "SECMAN_BACKEND_URL": "https://secman.example",
              "SECMAN_ADMIN_NAME": "operator", "SECMAN_ADMIN_PASS": secrets.token_urlsafe()}
    for owners in ([], RuntimeError("Owner lookup failed")):
      with self.subTest(owners=type(owners).__name__):
        secman = Mock()
        secman.ensure_workgroup.return_value = 7
        with patch.dict(os.environ, config, clear=True), \
             patch.object(read, "GraphTokenProvider"), \
             patch.object(read, "SecmanClient", return_value=secman), \
             patch.object(read, "graph_get_all", side_effect=[groups, owners, []]), \
             redirect_stdout(io.StringIO()):
          read.main(["--import"])
        secman.ensure_workgroup.assert_called_once_with("AWS-X", None)
        secman.add_members.assert_called_once_with(7, [])

  def test_ad_import_reads_and_persists_canonical_group_owner(self):
    group_id = "123e4567-e89b-12d3-a456-426614174000"
    groups = [{"id": group_id, "displayName": "AWS-X", "mail": "aws-x@example.com"}]
    owners = [{"id": "owner", "mail": "Owner@Example.COM", "userPrincipalName": None}]
    members = [{"id": "member", "mail": "member@example.com", "userPrincipalName": None}]
    secman = Mock()
    secman.ensure_workgroup.return_value = None
    config = {
      "AZURE_TENANT_ID": "tenant",
      "AZURE_CLIENT_ID": "client",
      "AZURE_CLIENT_SECRET": secrets.token_urlsafe(),
      "SECMAN_BACKEND_URL": "https://secman.example",
      "SECMAN_ADMIN_NAME": "operator",
      "SECMAN_ADMIN_PASS": secrets.token_urlsafe(),
    }
    output = io.StringIO()
    with patch.dict(os.environ, config, clear=True), \
         patch.object(read, "GraphTokenProvider"), \
         patch.object(read, "SecmanClient", return_value=secman), \
         patch.object(read, "graph_get_all", side_effect=[groups, owners, members]) as get_all, \
         redirect_stdout(output):
      self.assertIsNone(read.main(["--import", "--dry-run"]))
    self.assertIn(f"/groups/{group_id}/owners/microsoft.graph.user", get_all.call_args_list[1].args[1])
    self.assertIn("AD group owner email: owner@example.com", output.getvalue())
    secman.ensure_workgroup.assert_called_once_with("AWS-X", "owner@example.com")
    secman.add_members.assert_called_once_with(None, ["member@example.com"])

  def test_cli_dry_run_then_apply_twice_without_azure_credentials(self):
    fixture = FixtureClient()
    session = Mock(headers={})
    session.cookies.get.return_value = secrets.token_urlsafe()
    def get(url, **kwargs):
      self.assertFalse(kwargs["allow_redirects"])
      data = fixture.get_json(urlsplit(url).path, kwargs.get("params"))
      return Mock(status_code=200, json=lambda: data)
    def post(url, **kwargs):
      self.assertFalse(kwargs["allow_redirects"])
      if urlsplit(url).path == "/api/auth/login":
        return Mock(ok=True, is_redirect=False, json=lambda: {"roles": ["ADMIN"]})
      fixture.reconcile_owner_accounts(int(urlsplit(url).path.split("/")[3]), kwargs["json"]["expectedAccounts"])
      return Mock(status_code=200)
    def delete(url, **kwargs):
      self.assertFalse(kwargs["allow_redirects"])
      fixture.remove_assets(int(urlsplit(url).path.split("/")[3]), kwargs["json"]["assetIds"])
      return Mock(status_code=204)
    session.get.side_effect, session.post.side_effect, session.delete.side_effect = get, post, delete
    config = {"SECMAN_BACKEND_URL": "https://secman.example", "SECMAN_ADMIN_NAME": "operator",
              "SECMAN_ADMIN_PASS": secrets.token_urlsafe()}
    with patch.dict(os.environ, config, clear=True), patch.object(read.requests, "Session", return_value=session):
      summaries = []
      for args in (["sync-workgroup-assets", "--dry-run"], ["sync-workgroup-assets"], ["sync-workgroup-assets"]):
        output = io.StringIO()
        with redirect_stdout(output):
          self.assertEqual(0, read.main(args))
        summaries.append(json.loads(output.getvalue()))
      self.assertEqual([1, 1, 0], [item["relationships_to_add"] for item in summaries])
      self.assertEqual([0, 1, 0], [item["relationships_added"] for item in summaries])
      self.assertEqual([0, 0, 0], [item["relationships_to_remove"] for item in summaries])
      self.assertEqual([0, 0, 0], [item["relationships_removed"] for item in summaries])
      self.assertEqual(["000000000001"], fixture.synced_accounts[1])
      self.assertEqual([], fixture.removals)
      self.assertEqual(3, session.close.call_count)
      self.assertTrue(session.verify)
      self.assertEqual(9, session.post.call_count)  # Three logins, two assignment batches.
      self.assertEqual(0, session.delete.call_count)

  def test_error_logs_do_not_expose_credentials_or_response_details(self):
    sentinel = secrets.token_urlsafe()
    config = {"SECMAN_BACKEND_URL": "https://secman.example", "SECMAN_ADMIN_NAME": "operator",
              "SECMAN_ADMIN_PASS": sentinel}
    with patch.dict(os.environ, config, clear=True), patch.object(read.requests, "Session") as factory:
      factory.return_value.post.side_effect = read.requests.ConnectionError(sentinel)
      with self.assertLogs("adread.sync") as logs, redirect_stdout(io.StringIO()) as output:
        self.assertEqual(1, read.main(["sync-workgroup-assets"]))
      self.assertNotIn(sentinel, " ".join(logs.output) + output.getvalue())

  def test_insecure_sync_is_rejected_before_login(self):
    with patch.dict(os.environ, {}, clear=True), patch.object(read, "SecmanClient") as factory:
      with self.assertRaises(SystemExit):
        read.main(["sync-workgroup-assets", "--insecure"])
      factory.assert_not_called()

  def test_system_ca_switch_injects_native_trust_before_sync(self):
    with patch.object(read.truststore, "inject_into_ssl") as inject, \
         patch("sync_workgroup_assets.run_command", return_value=0) as run_command:
      self.assertEqual(0, read.main(["sync-workgroup-assets", "--dry-run", "--use-system-ca"]))
    inject.assert_called_once_with()
    run_command.assert_called_once_with(read.SecmanClient, True)

  def test_missing_configuration_has_nonzero_exit(self):
    with patch.dict(os.environ, {}, clear=True):
      self.assertEqual(1, read.main(["sync-workgroup-assets"]))

  def test_non_https_and_credential_urls_are_rejected(self):
    config = {"SECMAN_ADMIN_NAME": "operator", "SECMAN_ADMIN_PASS": secrets.token_urlsafe()}
    for url in ("http://secman.example", "https://user@secman.example", "https://secman.example?key=value"):
      with self.subTest(url=url), patch.dict(os.environ, {**config, "SECMAN_BACKEND_URL": url}, clear=True):
        with patch.object(read, "SecmanClient") as factory, redirect_stdout(io.StringIO()):
          self.assertEqual(1, read.main(["sync-workgroup-assets"]))
        factory.assert_not_called()


if __name__ == "__main__":
  unittest.main()
