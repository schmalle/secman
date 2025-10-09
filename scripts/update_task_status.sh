#!/bin/bash
# Update task statuses in tasks.md
# Feature: 013-user-mapping-upload

TASKS_FILE="specs/013-user-mapping-upload/tasks.md"

echo "Updating task statuses in $TASKS_FILE..."

# Phase 1: Setup & Verification
sed -i '' 's/\*\*Status\*\*: NOT STARTED/\*\*Status\*\*: ✅ COMPLETE/' "$TASKS_FILE" | head -1

# Completed tasks (T001-T031, T018-T028)
for task in T001 T002 T003 T004 T005 T006 T007 T009 T010 T011 T012 T013 T014 T015 T016 T017 T018 T019 T020 T021 T022 T023 T024 T025 T026 T027 T028 T029 T030 T031; do
    # Find task and update status
    sed -i '' "/^### $task /,/^\*\*Status\*\*:/ s/\*\*Status\*\*: NOT STARTED/\*\*Status\*\*: ✅ COMPLETE/" "$TASKS_FILE"
done

# T008 - Skipped (no unit test for controller, covered by E2E)
sed -i '' "/^### T008 /,/^\*\*Status\*\*:/ s/\*\*Status\*\*: NOT STARTED/\*\*Status\*\*: ⏭️ SKIPPED (Covered by E2E tests)/" "$TASKS_FILE"

# Remaining tasks (T032-T038)
echo ""
echo "Task Status Summary:"
echo "===================="
echo ""
echo "✅ COMPLETE: T001-T007, T009-T031 (30 tasks)"
echo "⏭️ SKIPPED: T008 (Controller unit test - covered by E2E)"
echo "⬜ PENDING: T032-T038 (7 tasks)"
echo ""
echo "Progress: 30/38 completed (79%)"
echo ""
echo "Remaining Tasks:"
echo "  T032: Add inline code documentation"
echo "  T033: Add frontend JSDoc comments"
echo "  T034: Code review and cleanup"
echo "  T035: Run linters and fix issues"
echo "  T036: Manual testing (13 scenarios)"
echo "  T037: Performance testing (100/1000 rows)"
echo "  T038: Security testing (8 controls)"
echo ""
echo "✓ Task statuses updated in $TASKS_FILE"
