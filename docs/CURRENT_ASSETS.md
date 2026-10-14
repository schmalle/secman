# Current asset visibility

SecMan operational inventory includes only assets observed in the inclusive rolling UTC window `[now - 72 hours, now]`. Missing and future evidence is excluded. This rule applies to ADMIN, SECCHAMPION and ordinary users, in addition to existing workgroup, AWS account, AD domain and sharing grants.

Falcon-managed assets use `asset.crowdstrike_agent_seen_at`, Falcon's actual last sensor contact. The durable `crowdstrike_managed` marker identifies this population; rollout marks existing Falcon identity bindings and source evidence. Source hostname, import timestamp, sensor timestamp and legacy CrowdStrike ownership also identify it. Editing ownership cannot remove established Falcon management. Imports, edits, merges and vulnerability insertion do not prove sensor contact. A scan of a Falcon-managed asset cannot substitute for missing or stale Falcon contact.

Other assets use `asset.verified_observed_at`. Nmap and Masscan observations with a valid source timestamp, and successful non-web integration runs, advance this timestamp. Scan timestamps are normalized to UTC; missing or malformed XML timestamps retain compatibility metadata but do not count as verified observations. Web integration runs require successful, reachable inventory evidence. Zero-finding observations count. Failed runs, future observations and older observations do not advance it. Administrative scanner imports through `PUT /api/assets/import` may provide an explicit ISO-8601 `observedAt`; omitted observation means metadata only. Future `observedAt` is rejected. A manual asset creation has no observation evidence and remains hidden until observed.

The legacy `last_seen` field remains for compatibility with ingestion. It is not the visibility boundary because older writers stamped processing time. Current asset REST, MCP and export representations use actual observation evidence for their displayed last-seen value.

## Reads and storage

`AssetCurrentnessSql` and `AssetCurrentnessPolicy` define currentness; `AssetFilterService` retains responsibility for authorization. Operational reads filter before pagination, counting and aggregation. CrowdStrike lookup responses carry actual `observedAt` evidence; the HTTP read guard rechecks it on every request, including cached and forced live results. Missing, future or stale contact cannot be shown through the lookup page.

Native current vulnerability queries require both existing snapshot/severity eligibility and current asset eligibility. Materialized heatmap and outdated-asset readers check current asset IDs at read time, so waiting for an import cannot keep stale assets visible.

`canAccessAsset` and `getAuthorizedAssetIds` refer to stored-record grants. `canReadCurrentAsset` and operational accessible-asset methods additionally apply currentness. Ingestion, scanner target discovery, relationship management, cleanup and historical risk-assessment access retain authorized stored lookup. This allows an existing hidden asset to receive new evidence and reappear under the same ID. The ADMIN internet-facing target endpoint and scanner subject discovery intentionally include authorized configured targets that are not current; this is an ingestion surface, not the operational asset inventory.

Hiding does not delete assets, findings, assignments, audit records or assessments. Existing retention and deletion policies remain independent. Historical records are evidence of the time they describe, not a list of current inventory. Repository-only integration and EOL records retain their separate lifecycle.

## Derived data and caches

Statistics and KPI cache entries carry `valid_until`, computed from the earliest expiry of the assets present when calculation began. Legacy cache entries are unavailable until recalculated. Reads reject expired entries; a background expiry check refreshes them every minute. Statistics use their existing live fallback, while a KPI without a valid cache returns unavailable rather than a stale percentage. Request handlers do not trigger KPI recomputation.

Vulnerability list totals are calculated from current inventory. Client-supplied `knownTotal` and the former fixed-duration count caches cannot override a count after assets age out. Existing vulnerability-age and exception rules remain independent of observation age.

The EDR KPI population consists of current EC2 assets, including non-Falcon assets with verified observations; its sensor-coverage condition and NO_EDR exclusions remain separate. The AWS clean-server KPI retains its documented seen-today scope, intersected with current inventory. Stored-population safety brakes used for deletion or matching are unchanged.

## Import and repair scripts

`./scripts/import.sh` uses three-day Falcon discovery in both regular and isolated modes. `CROWDSTRIKE_IMPORT_LAST_SEEN_DAYS` defaults to `3`; the regular wrapper caps zero or wider settings to `3`. A narrower configured sensor-retention window may further restrict discovery. Visibility is always 72 hours and cannot be widened through an import setting. `CROWDSTRIKE_SENSOR_RETENTION_DAYS` remains a distinct destructive-retention/import-admission setting; it is not automatically enabled by visibility.

Direct CLI queries may deliberately request a wider source window for diagnosis. This does not make imported stale assets visible. Hostname-specific source lookup retains its documented discovery behavior; saved inventory is still subject to backend currentness.

Inventory repair, AWS reconciliation and product ingestion must resolve stored assets rather than assuming a current asset list is the complete database. Do not infer observation from a repair timestamp, account mapping, product upload or manual vulnerability addition.

## Migration and verification

`V290__verified_asset_observation.sql` adds indexed non-Falcon observation evidence and cache expiry. It backfills successful non-web integration run timestamps. Legacy scan-result timestamps are not backfilled because their parser could use server-local time or an upload-time fallback. It does not backfill from old `last_seen`, creation, update or import time. Legacy web assets require a fresh reachable observation; legacy scan-only assets require a fresh timestamped scan. Existing assets with no trustworthy evidence become hidden on rollout.

Tests must seed explicit observation evidence for fixtures intended to appear as current. The common backend factory does this; stale, null and future-evidence tests must set their own timestamps. Shell fixtures use `secman_test_mark_asset_observed` only after verifying disposable database ownership. Browser fixtures can use the administrative import's explicit `observedAt`. These are test evidence, not a change to production manual-create semantics.

Run backend regression tests through `./scripts/runbackendtests.sh` and browser tests through `./scripts/test/run-isolated-e2e.sh`. Cover boundaries, timestamp provenance, role scopes, direct reads, paging totals, exports, MCP, derived-data expiry and re-observation. The normal database and running stack are not test targets.
