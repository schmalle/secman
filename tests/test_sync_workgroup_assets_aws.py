"""Exercise the AWS launcher and real shared helper without contacting AWS/SecMan."""

import json
import os
from pathlib import Path
import secrets
import shutil
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]


@unittest.skipUnless(shutil.which("jq"), "AWS launchers require jq")
class AwsLauncherTests(unittest.TestCase):
  def setUp(self):
    self.directory = tempfile.TemporaryDirectory(prefix="secman-sync-aws-")
    self.addCleanup(self.directory.cleanup)
    self.fixture = Path(self.directory.name)
    self.secret = {
      "SECMAN_BACKEND_BASE_URL": "https://secman.example.com",
      "SECMAN_ADMIN_NAME": "fixture-admin",
      "SECMAN_ADMIN_PASS": secrets.token_urlsafe(32) + " ' \" $ ` ;\n",
    }
    self.env = {key: value for key, value in os.environ.items()
                if not key.startswith(("SECMAN_", "AWS_", "BASH_FUNC_"))}
    self.env.pop("BASH_ENV", None)
    self.env.update({
      "SECMAN_AWS_SECRET_ID": "prod/secman/credentials",
      "AWS_REGION": "eu-central-1",
      "AWS_PROFILE": "fixture-profile",
      "SDKMAN_DIR": str(self.fixture / "no-sdkman"),
      "NVM_DIR": str(self.fixture / "no-nvm"),
      "SECMAN_TEST_DIR": str(self.fixture),
      "SECMAN_TEST_PYTHON": sys.executable,
    })
    self.write_tool("aws", '''
with (root / "aws-calls.jsonl").open("a") as calls:
    calls.write(json.dumps({"args": sys.argv[1:], "profile": os.getenv("AWS_PROFILE")}) + "\\n")
if os.getenv("SECMAN_TEST_AWS_FAIL"):
    sys.exit(42)
print(os.environ["SECMAN_TEST_SECRET"])
''')
    self.write_tool("uv", '''
names = ("SECMAN_BACKEND_URL", "SECMAN_ADMIN_NAME", "SECMAN_ADMIN_PASS",
         "SECMAN_INSECURE", "REQUESTS_CA_BUNDLE", "AWS_PROFILE",
         "AWS_ACCESS_KEY_ID", "SECMAN_MCP_KEY", "SECMAN_AWS_SECRET_JSON")
(root / "child.json").write_text(json.dumps({
    "args": sys.argv[1:], "env": {key: os.getenv(key) for key in names}}))
sys.exit(int(os.getenv("SECMAN_TEST_CHILD_EXIT", "0")))
''')
    self.write_tool("pass-cli", 'raise RuntimeError("Proton Pass must not be called")\n')

  def write_tool(self, name, body):
    (self.fixture / f"{name}.py").write_text(
      'import json, os, sys\nfrom pathlib import Path\n'
      'root = Path(os.environ["SECMAN_TEST_DIR"])\n' + body)
    executable = self.fixture / name
    executable.write_text(
      '#!/bin/bash\nexec "$SECMAN_TEST_PYTHON" '
      f'"$SECMAN_TEST_DIR/{name}.py" "$@"\n')
    executable.chmod(0o755)

  def run_wrapper(self, *args, payload=None):
    self.env["SECMAN_TEST_SECRET"] = json.dumps(self.secret) if payload is None else payload
    # Preload the real bootstrap so its cron PATH cannot shadow our fake tools.
    # Restore an unset secret ID afterwards to test the launcher's explicit-ID guard.
    harness = '''
set -euo pipefail
secman_test_id="${SECMAN_AWS_SECRET_ID-}"
source "$1/scripts/lib/aws-secrets.sh"
SECMAN_AWS_SECRET_ID="$secman_test_id"
export PATH="$2:$PATH"
secman_test_wrapper="$1/scripts/sync-workgroup-assets-aws.sh"
shift 2
source "$secman_test_wrapper" "$@"
'''
    result = subprocess.run(["bash", "-c", harness, "--", str(ROOT), str(self.fixture), *args],
                            env=self.env, cwd=self.fixture, text=True, capture_output=True,
                            timeout=15)
    self.assertNotIn(self.secret.get("SECMAN_ADMIN_PASS") or "unused-secret-marker",
                     result.stdout + result.stderr)
    return result

  def child(self):
    return json.loads((self.fixture / "child.json").read_text())

  def test_fetch_once_and_forward_arguments_and_credentials(self):
    self.env.update({"SECMAN_BACKEND_URL": "https://stale.example.com",
                     "SECMAN_ADMIN_NAME": "stale-user",
                     "SECMAN_ADMIN_PASS": secrets.token_urlsafe(24)})
    result = self.run_wrapper("--dry-run", "argument with spaces")
    self.assertEqual(0, result.returncode, result.stderr)
    self.assertEqual("", result.stdout)
    calls = [json.loads(line) for line in (self.fixture / "aws-calls.jsonl").read_text().splitlines()]
    self.assertEqual([{"args": ["secretsmanager", "get-secret-value", "--secret-id",
                               "prod/secman/credentials", "--region", "eu-central-1",
                               "--query", "SecretString", "--output", "text"],
                       "profile": "fixture-profile"}], calls)
    child = self.child()
    self.assertEqual(["run", "--locked", "--project", str(ROOT / "src/adread"),
                      "python", str(ROOT / "src/adread/read.py"), "sync-workgroup-assets",
                      "--dry-run", "argument with spaces"], child["args"])
    for key in ("SECMAN_ADMIN_NAME", "SECMAN_ADMIN_PASS"):
      self.assertEqual(self.secret[key], child["env"][key])
    self.assertEqual(self.secret["SECMAN_BACKEND_BASE_URL"], child["env"]["SECMAN_BACKEND_URL"])
    self.assertNotIn(self.secret["SECMAN_ADMIN_PASS"], " ".join(child["args"]))

  def test_existing_production_url_field_and_region_fallback(self):
    self.secret["SECMAN_BACKEND_URL"] = self.secret.pop("SECMAN_BACKEND_BASE_URL")
    self.env.pop("AWS_REGION")
    self.env["AWS_DEFAULT_REGION"] = "eu-west-1"
    result = self.run_wrapper()
    self.assertEqual(0, result.returncode, result.stderr)
    self.assertEqual("sync-workgroup-assets", self.child()["args"][-1])
    self.assertEqual(self.secret["SECMAN_BACKEND_URL"], self.child()["env"]["SECMAN_BACKEND_URL"])
    self.assertIn("eu-west-1", (self.fixture / "aws-calls.jsonl").read_text())

  def test_canonical_url_wins_and_unneeded_secret_fields_are_not_exported(self):
    self.secret.update({"SECMAN_BACKEND_URL": "https://legacy.example.com",
                        "SECMAN_SSL_ACCEPT_ALL": "true", "SECMAN_INSECURE": "true",
                        "SECMAN_AWS_ACCESS_KEY_ID": secrets.token_hex(16),
                        "SECMAN_MCP_KEY": secrets.token_urlsafe(32)})
    self.env["REQUESTS_CA_BUNDLE"] = str(self.fixture / "trusted-ca.pem")
    self.assertEqual(0, self.run_wrapper("--dry-run").returncode)
    child_env = self.child()["env"]
    self.assertEqual(self.secret["SECMAN_BACKEND_BASE_URL"], child_env["SECMAN_BACKEND_URL"])
    self.assertEqual("fixture-profile", child_env["AWS_PROFILE"])
    self.assertEqual(self.env["REQUESTS_CA_BUNDLE"], child_env["REQUESTS_CA_BUNDLE"])
    for key in ("SECMAN_INSECURE", "AWS_ACCESS_KEY_ID", "SECMAN_MCP_KEY", "SECMAN_AWS_SECRET_JSON"):
      self.assertIsNone(child_env[key])

  def test_missing_secret_id_stops_before_aws_or_child(self):
    self.env.pop("SECMAN_AWS_SECRET_ID")
    result = self.run_wrapper()
    self.assertNotEqual(0, result.returncode)
    self.assertIn("Set SECMAN_AWS_SECRET_ID", result.stderr)
    self.assertFalse((self.fixture / "aws-calls.jsonl").exists())
    self.assertFalse((self.fixture / "child.json").exists())

  def test_aws_failure_stops_before_child(self):
    self.env["SECMAN_TEST_AWS_FAIL"] = "1"
    result = self.run_wrapper()
    self.assertNotEqual(0, result.returncode)
    self.assertIn("failed to read secret", result.stderr)
    self.assertFalse((self.fixture / "child.json").exists())

  def test_invalid_or_incomplete_secret_never_uses_ambient_credentials(self):
    self.env.update({"SECMAN_BACKEND_URL": "https://stale.example.com",
                     "SECMAN_ADMIN_NAME": "stale-user",
                     "SECMAN_ADMIN_PASS": secrets.token_urlsafe(24)})
    payloads = ["", "None", "{invalid-json", "[]", "null"]
    payloads.append(json.dumps(self.secret) + "\n" + json.dumps(self.secret))
    for value in (False, "", 12, {}):
      payloads.append(json.dumps({**self.secret, "SECMAN_BACKEND_BASE_URL": value,
                                 "SECMAN_BACKEND_URL": "https://legacy.example.com"}))
    for key in self.secret:
      for value in (None, "", 12, {}, "invalid\x00field"):
        payloads.append(json.dumps({**self.secret, key: value}))
    for payload in payloads:
      with self.subTest(payload_shape=payload[:20]):
        result = self.run_wrapper(payload=payload)
        self.assertNotEqual(0, result.returncode)
        self.assertFalse((self.fixture / "child.json").exists())
        self.assertEqual("", result.stdout)

  def test_child_exit_status_and_inherited_tls_policy_are_preserved(self):
    self.env.update({"SECMAN_TEST_CHILD_EXIT": "2", "SECMAN_INSECURE": "true"})
    self.assertEqual(2, self.run_wrapper("--insecure").returncode)
    self.assertEqual("true", self.child()["env"]["SECMAN_INSECURE"])
    self.assertEqual("--insecure", self.child()["args"][-1])


if __name__ == "__main__":
  unittest.main()
