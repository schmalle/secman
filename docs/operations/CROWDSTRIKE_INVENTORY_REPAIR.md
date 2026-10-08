# CrowdStrike inventory correction

The normal import retains the 30-day `SERVER_FAMILY` scope. This includes Falcon's
`Server` and `Domain Controller` categories. It is not the same population as the
Falcon dashboard's servers seen in the last day. Offline sensors are not proof of
retirement, and this correction does not delete machines merely to match that count.

The October 2026 investigation also found two independent correctness defects:
cloud instances sharing a hostname were grouped together, and reconciliation wrote
its execution time into the sensor-contact field. The corrected selector groups
cloud records by instance/account; a record without an instance cannot bridge two
instances. Conflicting stored cloud identity fails closed. New imports retain
provider classification and UTC contact times, including devices with no findings.
Falcon category is separate from generic asset type: domain controllers remain
servers for existing risk rules, and user-managed inventory types are preserved.

Inventory provides an explicit Falcon contact filter: within 24 hours, older than
24 hours, and no verified timestamp. The EDR KPI retains its documented seven-day
window, now measured from actual UTC contact. Import time remains a separate field.

## Rollout

1. Obtain a consistent database backup before deployment/import and verify the dump.
   Keep it outside version control with restrictive permissions. Include all tables
   because vulnerability imports replace snapshots and may replace proven enrollments.
2. Deploy backend, shared library and rebuilt CLI together. Migration V284 adds
   the provider-category column, enrollment history and contact-repair audit tables;
   it does not rewrite existing inventory values.
3. Stop the normal backend and any import writers. Capture the repair manifest:
   `./scripts/repair-crowdstrike-inventory.sh --preview > contact-preview.jsonl`.
   The first line is the candidate count; following lines contain asset IDs and
   before/after contact times. Preview performs no database writes.
4. Apply with `./scripts/repair-crowdstrike-inventory.sh --apply`. The command uses
   the local MariaDB client's connection defaults and defaults to database `secman`.
   `SECMAN_REPAIR_DATABASE` can explicitly select another local database.
   It refuses writes while the normal or isolated backend port is listening.
5. Preserve the printed run UUID. Each batch of at most 500 assets writes its audit
   and correction atomically. Only one-current-binding records with a non-future,
   known Falcon timestamp qualify. Ambiguous and unknown records remain unchanged.
6. Start the backend through `./scripts/startbackenddev.sh`, rebuild the CLI, and run
   `./scripts/import.sh`. This refreshes classification and current findings and
   restores separately identified cloud instances omitted by hostname grouping.
   Review failed hosts and the final process exit code; partial imports are not
   successful validation. Never relax the identity guard to force an import through.
7. Verify source contact/type distributions, per-AID outcomes, restored instance
   identities, and completion of the deferred derived-data refresh. Compare Falcon
   and SecMan using the same observation window and include domain controllers
   explicitly. Counts can move during a live traversal; do not target a fixed total.

Enrollment history is separate from the current binding, so retired AIDs remain
available as evidence without making them eligible for matching or resurrection.
Bulk asset deletion removes operational integration dependents before asset rows;
scanner configuration remains intact. Enrollment and repair audit records survive.

## Reversal and limits

With writers stopped, `./scripts/repair-crowdstrike-inventory.sh --rollback RUN_UUID`
restores contact values only where the current value still equals that run's result.
It preserves newer observations. This reverses the contact repair only; use the
pre-import database backup for an entire inventory/finding rollback.

The repair does not infer deleted machines from missing Falcon enumeration entries,
merge ambiguous identities, discard user workgroups, or classify operating systems
by name. Missing source evidence requires review. The current integration is a
single configured Falcon tenant; this change does not introduce multi-tenant routing.

## Validation

- Shared tests cover independent cloud instances, proven reenrollment, provider cursor
  traversal, and incomplete results.
- Backend tests cover source-time reconciliation, read-only previews, and authorized
  inventory recency filtering.
- `scripts/test/test-crowdstrike-contact-repair.sh` runs only through the database-only
  isolated runner and checks preview, evidence limits, repeated application and rollback.
- Full build, frontend production build, isolated exception lifecycle, dual-role JS
  scan, and a real operational import are separate gates.
