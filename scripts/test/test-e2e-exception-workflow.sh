#!/bin/bash
#
# E2E Vulnerability Exception Workflow Test Script
# Feature: 063-e2e-vuln-exception
#
# Tests the complete vulnerability exception request workflow via MCP:
# 1. Delete all assets (clean environment)
# 2. Create test user apple@schmall.io
# 3. Add asset with 10-day vulnerability (not overdue)
# 4. Verify user sees no overdue vulnerabilities
# 5. Add 40-day CRITICAL vulnerability (overdue)
# 6. User creates exception request
# 7. Admin approves exception request
# 8. Cleanup test data
#
# Usage:
#   ./scripts/test/test-e2e-exception-workflowsupport.sh
#   BASE_URL=http://localhost:8080 API_KEY=sk-xxx ./scripts/test/test-e2e-exception-workflowsupport.sh
#   ./scripts/test/test-e2e-exception-workflowsupport.sh --verbose
#   ./scripts/test/test-e2e-exception-workflowsupport.sh --help
#

op run --env-file ./secman.env -- ./scripts/test/test-e2e-exception-workflowsupport.sh
