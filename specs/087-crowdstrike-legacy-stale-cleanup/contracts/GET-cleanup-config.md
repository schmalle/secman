# Contract: `GET /api/crowdstrike/cleanup/config`

**Auth**: `@Secured("ADMIN")` (existing)
**Backend**: `CrowdStrikeCleanupController.getConfig`
**Status codes**: 200 OK on success; 401/403 on auth failure (existing).

## Request

No body, no query parameters.

```http
GET /api/crowdstrike/cleanup/config HTTP/1.1
Authorization: Bearer <admin-jwt>
```

## Response — 200 OK

```json
{
  "enabled": false,
  "staleDays": 30,
  "maxDeletePercent": 10,
  "cron": "0 30 2 * * ?",
  "includeLegacy": false
}
```

### Field reference

| Field | Type | Source | Notes |
|---|---|---|---|
| `enabled` | boolean | `secman.crowdstrike.cleanup.enabled` | Existing |
| `staleDays` | int | `secman.crowdstrike.cleanup.stale-days` | Existing |
| `maxDeletePercent` | int | `secman.crowdstrike.cleanup.max-delete-percent` | Existing |
| `cron` | string | hardcoded `"0 30 2 * * ?"` | Existing |
| `includeLegacy` | boolean | `secman.crowdstrike.cleanup.include-legacy` (default `false`) | **NEW (Feature 087)** — UI initializes the legacy-toggle from this value (FR-009 + SC-006) |

## Backward compatibility

Existing clients that deserialize this DTO ignore unknown fields (Micronaut's default Jackson configuration). The new `includeLegacy` field is therefore additive. No version bump.

## Verification

- **Unit**: existing controller test extends to assert the new field is present and reflects the `@Value` injection.
- **Manual**: `curl -H "Authorization: Bearer $TOKEN" $SECMAN_HOST/api/crowdstrike/cleanup/config | jq .includeLegacy` returns `false` after deploy with default config.
