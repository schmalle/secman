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


def user(uid=1, email="a@example.com", groups=(1,)):
  return {"id": uid, "email": email, "workgroups": [{"id": gid} for gid in groups]}


def mapping(account="000000000001", email="a@example.com"):
  return {"id": 1, "awsAccountId": account, "email": email}


def asset(aid=10, account="000000000001", groups=()):
  return {"id": aid, "name": f"System {aid}", "cloudAccountId": account,
          "workgroups": [{"id": gid} for gid in groups]}


class FixtureClient:
  def __init__(self, users=None, mappings=None, assets=None):
    self.groups = [{"id": 1, "name": "X"}, {"id": 2, "name": "Y"}, {"id": 3, "name": "Manual"}]
    self.users = [user()] if users is None else users
    self.mappings = [mapping()] if mappings is None else mappings
    self.assets = [asset()] if assets is None else assets
    self.reads = []
    self.writes = []

  def get_json(self, path, params=None):
    self.reads.append((path, params))
    if path == "/api/workgroups":
      return deepcopy(self.groups)
    if path == "/api/users":
      return deepcopy(self.users)
    if path == "/api/assets":
      return deepcopy(self.assets)
    rows = self.mappings if path.endswith("/current") else []
    page = params["page"]
    return {"content": deepcopy(rows[page * BATCH_SIZE:(page + 1) * BATCH_SIZE]),
            "totalPages": (len(rows) + BATCH_SIZE - 1) // BATCH_SIZE, "page": page}

  def assign_assets(self, gid, ids):
    self.writes.append((gid, list(ids)))
    for item in self.assets:
      if item["id"] in ids and {"id": gid} not in item["workgroups"]:
        item["workgroups"].append({"id": gid})


class MatchingTests(unittest.TestCase):
  def plan(self, **kwargs):
    client = FixtureClient(**kwargs)
    return build_plan(client.groups, client.users, client.mappings, client.assets)

  def test_basic_mapping(self):
    plan = self.plan()
    self.assertEqual({1: [10]}, plan.additions)
    self.assertEqual((3, 1, 1, 1, 1, 1, 0, 0), (
      plan.workgroups_evaluated, plan.members_evaluated, plan.unique_email_addresses,
      plan.aws_accounts_matched, plan.assets_matched, plan.relationships_to_add,
      plan.relationships_to_remove, plan.errors))

  def test_no_matching_account(self):
    self.assertEqual({}, self.plan(mappings=[]).additions)

  def test_multiple_accounts(self):
    plan = self.plan(mappings=[mapping(), mapping("000000000002")],
                     assets=[asset(), asset(20, "000000000002")])
    self.assertEqual({1: [10, 20]}, plan.additions)

  def test_multiple_members_and_accounts(self):
    plan = self.plan(users=[user(), user(2, "b@example.com")],
                     mappings=[mapping(), mapping("000000000002", "b@example.com")],
                     assets=[asset(), asset(20, "000000000002")])
    self.assertEqual({1: [10, 20]}, plan.additions)

  def test_duplicate_ownership_and_records(self):
    plan = self.plan(users=[user(), user(2, "b@example.com")],
                     mappings=[mapping(), mapping(), mapping(email="b@example.com")],
                     assets=[asset(), asset()])
    self.assertEqual({1: [10]}, plan.additions)
    self.assertEqual(1, plan.assets_matched)

  def test_member_in_multiple_workgroups(self):
    self.assertEqual({1: [10], 2: [10]}, self.plan(users=[user(groups=(1, 2))]).additions)

  def test_multiple_owners_in_different_workgroups(self):
    plan = self.plan(users=[user(), user(2, "d@example.com", (2,))],
                     mappings=[mapping(), mapping(email="d@example.com")])
    self.assertEqual({1: [10], 2: [10]}, plan.additions)

  def test_normalized_emails_and_account_whitespace(self):
    plan = self.plan(users=[user(email=" A.User@Example.COM ")],
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

  def test_accounts_on_assets_without_mapping_are_reported(self):
    plan = self.plan(mappings=[])
    self.assertEqual(1, plan.skipped_accounts)

  def test_assets_without_aws_and_domain_only_mappings_are_untouched(self):
    plan = self.plan(mappings=[mapping(None)], assets=[asset(account=None), asset(20, "")])
    self.assertEqual({}, plan.additions)
    self.assertEqual(0, plan.errors)

  def test_existing_manual_and_stale_links_are_preserved(self):
    client = FixtureClient(assets=[asset(groups=(2, 3)), asset(20, None, (3,))])
    synchronize(client, False)
    client.users = []
    before = deepcopy(client.assets)
    plan = synchronize(client, False)
    self.assertEqual(before, client.assets)
    self.assertEqual(0, plan.relationships_to_remove)

  def test_idempotency(self):
    client = FixtureClient()
    self.assertEqual(1, synchronize(client, False).relationships_added)
    self.assertEqual(0, synchronize(client, False).relationships_added)
    self.assertEqual([(1, [10])], client.writes)

  def test_dry_run_calculates_complete_plan_without_writes(self):
    client = FixtureClient()
    before = deepcopy(client.assets)
    plan = synchronize(client, True)
    self.assertEqual({1: [10]}, plan.additions)
    self.assertEqual(0, plan.relationships_added)
    self.assertEqual([], client.writes)
    self.assertEqual(before, client.assets)

  def test_malformed_records_do_not_stop_valid_records(self):
    plan = self.plan(users=[None, user(), user(2, "invalid")],
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
    plan = self.plan(users=[user(groups=(2, 1))], assets=[asset(20), asset(10)])
    self.assertEqual([(1, [10, 20]), (2, [10, 20])], list(plan.additions.items()))


class TransportTests(unittest.TestCase):
  def test_bulk_reads_and_bounded_assignment_batches(self):
    client = FixtureClient(assets=[asset(i) for i in range(1, 2 * BATCH_SIZE + 2)])
    plan = synchronize(client, False)
    self.assertEqual(5, len(client.reads))
    self.assertEqual([500, 500, 1], [len(ids) for _, ids in client.writes])
    self.assertEqual(1001, plan.relationships_added)

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

  def test_partial_assignment_failure_keeps_other_workgroups_and_reports_error(self):
    client = FixtureClient(users=[user(groups=(1, 2))])
    assign = client.assign_assets
    def assign_assets(gid, ids):
      if gid == 1:
        raise RuntimeError("HTTP 500")
      assign(gid, ids)
    client.assign_assets = assign_assets
    plan = synchronize(client, False)
    self.assertEqual(1, plan.errors)
    self.assertEqual([(2, [10])], client.writes)

  def test_client_uses_existing_bulk_contract_and_never_follows_redirects(self):
    with patch.object(read.requests, "Session") as factory:
      session = factory.return_value
      session.get.return_value.status_code = 200
      session.post.return_value.status_code = 200
      client = read.SecmanClient("https://secman.example", "operator", secrets.token_urlsafe())
      client.get_json("/api/workgroups")
      client.assign_assets(1, [10, 20])
      self.assertTrue(session.verify)
      self.assertFalse(session.get.call_args.kwargs["allow_redirects"])
      self.assertFalse(session.post.call_args.kwargs["allow_redirects"])
      self.assertEqual({"assetIds": [10, 20]}, session.post.call_args.kwargs["json"])
      self.assertEqual("https://secman.example/api/workgroups/1/assets", session.post.call_args.args[0])

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
    client._session.close()


class CommandTests(unittest.TestCase):
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
      fixture.assign_assets(int(urlsplit(url).path.split("/")[3]), kwargs["json"]["assetIds"])
      return Mock(status_code=200)
    session.get.side_effect, session.post.side_effect = get, post
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
      self.assertEqual([(1, [10])], fixture.writes)
      self.assertEqual(3, session.close.call_count)
      self.assertTrue(session.verify)
      self.assertEqual(4, session.post.call_count)  # Three logins, one asset batch.

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
