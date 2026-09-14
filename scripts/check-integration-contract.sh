#!/usr/bin/env bash
# Opt-in, database-free contract checks. Never starts SecMan or reads vault credentials.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
if [[ "${1:-}" != "--run" ]]; then
    echo "No tests run. Pass --run only when test execution is authorized."
    exit 2
fi
github_python="${SECMAN_GITHUB_TEST_PYTHON:-extensions/secman_ai_github/.venv/bin/python}"
visual_python="${SECMAN_VISUAL_TEST_PYTHON:-extensions/secman_visual_check/.venv/bin/python}"
web_python="${SECMAN_WEB_TEST_PYTHON:-extensions/secman_web_check/.venv/bin/python}"
for client in secman_ai_github secman_visual_check secman_web_check; do
    cmp docs/contracts/integration-run-v1.json "extensions/$client/tests/fixtures/integration-run-v1.json"
done
./gradlew :backendng:test \
    --tests '*IntegrationRunValidatorTest' \
    --tests '*IntegrationRunWriterTest' \
    --tests '*IntegrationHealthNotifierTest' \
    --tests '*IntegrationHealthRepositoryTest' \
    --tests '*IntegrationRelaySnapshotTest' --console=plain
"$github_python" -m pytest extensions/secman_ai_github/tests/test_integration_results.py \
    extensions/secman_ai_github/tests/test_cli_integration_results.py -q
"$visual_python" -m pytest extensions/secman_visual_check/tests/test_secman_integration_results.py -q
"$web_python" -m pytest extensions/secman_web_check/tests/test_secman_integration_results.py -q
