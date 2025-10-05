# Quickstart: Workgroup-Based Access Control

**Feature**: 008-create-an-additional
**Purpose**: End-to-end validation script for all acceptance scenarios
**Prerequisites**: Backend running, database migrated, test users created

## Test Data Setup

```bash
# Create test users (ADMIN creates these via /api/users)
ADMIN_USER_ID=1      # alice (ADMIN role)
VULN_USER_ID=2       # bob (VULN role)
USER1_ID=3           # charlie (USER role)
USER2_ID=4           # diana (USER role)

# Obtain JWT tokens
ADMIN_TOKEN=$(curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"alice","password":"admin123"}' | jq -r '.token')

BOB_TOKEN=$(curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"bob","password":"vuln123"}' | jq -r '.token')

CHARLIE_TOKEN=$(curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"charlie","password":"user123"}' | jq -r '.token')
```

---

## Scenario 1: Workgroup Creation (FR-001, FR-004, FR-006)

**Test**: ADMIN creates workgroup with valid name

```bash
# Create "Engineering" workgroup
curl -X POST http://localhost:8080/api/workgroups \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Engineering Team",
    "description": "All engineers and developers"
  }'

# Expected: 201 Created, returns workgroup ID
```

**Test**: Duplicate name validation (case-insensitive)

```bash
# Attempt to create "engineering team" (different case)
curl -X POST http://localhost:8080/api/workgroups \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "engineering team"
  }'

# Expected: 400 Bad Request, error: "Workgroup name already exists (case-insensitive)"
```

**Test**: Invalid characters validation

```bash
# Attempt to create workgroup with special characters
curl -X POST http://localhost:8080/api/workgroups \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "DevOps@2025!"
  }'

# Expected: 400 Bad Request, error: "Name must contain only letters, numbers, spaces, and hyphens"
```

**Create remaining test workgroups**:
```bash
# DevOps workgroup
curl -X POST http://localhost:8080/api/workgroups \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"name": "DevOps", "description": "DevOps team"}' \
  | jq -r '.id' > /tmp/devops_wg_id

DEVOPS_WG_ID=$(cat /tmp/devops_wg_id)

# Security workgroup
curl -X POST http://localhost:8080/api/workgroups \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"name": "Security", "description": "Security team"}' \
  | jq -r '.id' > /tmp/security_wg_id

SECURITY_WG_ID=$(cat /tmp/security_wg_id)

ENGINEERING_WG_ID=1  # Assuming first created workgroup gets ID 1
```

---

## Scenario 2: User Assignment to Workgroup (FR-007, FR-008, FR-009)

**Test**: Assign users to Engineering workgroup

```bash
# Assign charlie (USER role) to Engineering
curl -X POST http://localhost:8080/api/workgroups/$ENGINEERING_WG_ID/users \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d "{\"userIds\": [$USER1_ID]}"

# Expected: 200 OK, message: "1 users assigned to workgroup"
```

**Test**: Assign bob (VULN role) to Security workgroup

```bash
curl -X POST http://localhost:8080/api/workgroups/$SECURITY_WG_ID/users \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d "{\"userIds\": [$VULN_USER_ID]}"

# Expected: 200 OK
```

**Test**: View user's workgroups

```bash
curl -X GET http://localhost:8080/api/users/$USER1_ID/workgroups \
  -H "Authorization: Bearer $ADMIN_TOKEN"

# Expected: 200 OK, returns [{"id":1,"name":"Engineering Team",...}]
```

---

## Scenario 3: Asset Assignment to Workgroup (FR-011, FR-012, FR-013, FR-014)

**Setup**: Create test assets

```bash
# charlie creates asset manually (sets manual_creator_id)
ASSET1_ID=$(curl -X POST http://localhost:8080/api/assets \
  -H "Authorization: Bearer $CHARLIE_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "web-server-01",
    "type": "Server",
    "owner": "charlie",
    "ip": "192.168.1.10"
  }' | jq -r '.id')

# alice (ADMIN) creates asset manually
ASSET2_ID=$(curl -X POST http://localhost:8080/api/assets \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "db-server-01",
    "type": "Database",
    "owner": "alice",
    "ip": "192.168.1.20"
  }' | jq -r '.id')
```

**Test**: ADMIN assigns assets to workgroups

```bash
# Assign web-server-01 to Engineering workgroup
curl -X POST http://localhost:8080/api/workgroups/$ENGINEERING_WG_ID/assets \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d "{\"assetIds\": [$ASSET1_ID]}"

# Expected: 200 OK

# Assign db-server-01 to Security workgroup
curl -X POST http://localhost:8080/api/workgroups/$SECURITY_WG_ID/assets \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d "{\"assetIds\": [$ASSET2_ID]}"
```

**Test**: Regular user CANNOT assign workgroups (FR-014)

```bash
curl -X POST http://localhost:8080/api/workgroups/$DEVOPS_WG_ID/assets \
  -H "Authorization: Bearer $CHARLIE_TOKEN" \
  -H "Content-Type: application/json" \
  -d "{\"assetIds\": [$ASSET1_ID]}"

# Expected: 403 Forbidden, error: "Requires ADMIN role"
```

---

## Scenario 4: Filtered Asset Visibility (FR-015, FR-016)

**Test**: charlie (USER in Engineering) sees only Engineering assets + owned assets

```bash
curl -X GET http://localhost:8080/api/assets \
  -H "Authorization: Bearer $CHARLIE_TOKEN"

# Expected: 200 OK, returns web-server-01 only
# - Visible because charlie is in Engineering workgroup AND charlie created it
# - db-server-01 NOT visible (in Security workgroup, charlie not a member)
```

**Test**: diana (USER with no workgroups) sees only personally created assets (Scenario 8)

```bash
# diana creates an asset
DIANA_ASSET_ID=$(curl -X POST http://localhost:8080/api/assets \
  -H "Authorization: Bearer $DIANA_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "test-laptop",
    "type": "Laptop",
    "owner": "diana"
  }' | jq -r '.id')

# diana lists assets
curl -X GET http://localhost:8080/api/assets \
  -H "Authorization: Bearer $DIANA_TOKEN"

# Expected: 200 OK, returns test-laptop only (not in any workgroup, but diana created it)
```

---

## Scenario 5: Filtered Vulnerability Visibility (FR-017)

**Setup**: Import vulnerabilities for test assets

```bash
# Import vulnerability for web-server-01 (Engineering workgroup)
curl -X POST http://localhost:8080/api/import/upload-vulnerability-xlsx \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -F "file=@testdata/vulnerabilities-web-server.xlsx"

# Import vulnerability for db-server-01 (Security workgroup)
curl -X POST http://localhost:8080/api/import/upload-vulnerability-xlsx \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -F "file=@testdata/vulnerabilities-db-server.xlsx"
```

**Test**: charlie (Engineering) sees only Engineering vulnerabilities

```bash
curl -X GET http://localhost:8080/api/vulnerabilities/current \
  -H "Authorization: Bearer $CHARLIE_TOKEN"

# Expected: 200 OK, returns vulnerabilities for web-server-01 only
# - Visible because web-server-01 is in Engineering workgroup
# - db-server-01 vulnerabilities NOT visible
```

---

## Scenario 5a: Filtered Scan Results Visibility (FR-017a)

**Setup**: Upload scans

```bash
# charlie uploads nmap scan for web-server-01 (sets scan_uploader_id)
curl -X POST http://localhost:8080/api/import/upload-nmap-xml \
  -H "Authorization: Bearer $CHARLIE_TOKEN" \
  -F "file=@testdata/nmap-web-server.xml"

# alice uploads nmap scan for db-server-01
curl -X POST http://localhost:8080/api/import/upload-nmap-xml \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -F "file=@testdata/nmap-db-server.xml"
```

**Test**: charlie sees only scans for Engineering assets + scans he uploaded

```bash
curl -X GET http://localhost:8080/api/scans \
  -H "Authorization: Bearer $CHARLIE_TOKEN"

# Expected: 200 OK, returns scan for web-server-01 only
# - Visible because: (1) web-server-01 in Engineering workgroup, (2) charlie uploaded it
```

---

## Scenario 6: Admin Universal Access (FR-018)

**Test**: alice (ADMIN) sees ALL assets regardless of workgroups

```bash
curl -X GET http://localhost:8080/api/assets \
  -H "Authorization: Bearer $ADMIN_TOKEN"

# Expected: 200 OK, returns [web-server-01, db-server-01, test-laptop]
# - ALL assets visible despite being in different workgroups
```

**Test**: alice (ADMIN) sees ALL vulnerabilities

```bash
curl -X GET http://localhost:8080/api/vulnerabilities/current \
  -H "Authorization: Bearer $ADMIN_TOKEN"

# Expected: 200 OK, returns vulnerabilities from all assets
```

---

## Scenario 6a: VULN Role Respects Workgroups (FR-018a)

**Test**: bob (VULN in Security) sees only Security workgroup vulnerabilities

```bash
curl -X GET http://localhost:8080/api/vulnerabilities/current \
  -H "Authorization: Bearer $BOB_TOKEN"

# Expected: 200 OK, returns vulnerabilities for db-server-01 only
# - Visible because db-server-01 is in Security workgroup
# - web-server-01 vulnerabilities NOT visible (Engineering workgroup)
```

**Test**: VULN role does NOT bypass workgroup filtering

```bash
curl -X GET http://localhost:8080/api/assets \
  -H "Authorization: Bearer $BOB_TOKEN"

# Expected: 200 OK, returns db-server-01 only
# - Confirms VULN role follows same workgroup restrictions as USER role
```

---

## Scenario 7: Workgroup Deletion (FR-026)

**Test**: Delete workgroup clears all memberships

```bash
# Get workgroup details before deletion
curl -X GET http://localhost:8080/api/workgroups/$DEVOPS_WG_ID \
  -H "Authorization: Bearer $ADMIN_TOKEN"

# Expected: userCount: 0, assetCount: 0 (if no members assigned yet)

# Assign test data to DevOps workgroup
curl -X POST http://localhost:8080/api/workgroups/$DEVOPS_WG_ID/users \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d "{\"userIds\": [$USER2_ID]}"

curl -X POST http://localhost:8080/api/workgroups/$DEVOPS_WG_ID/assets \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d "{\"assetIds\": [$ASSET1_ID]}"

# Delete workgroup
curl -X DELETE http://localhost:8080/api/workgroups/$DEVOPS_WG_ID \
  -H "Authorization: Bearer $ADMIN_TOKEN"

# Expected: 204 No Content

# Verify user and asset still exist (FR-026: memberships removed but entities persist)
curl -X GET http://localhost:8080/api/users/$USER2_ID \
  -H "Authorization: Bearer $ADMIN_TOKEN"
# Expected: 200 OK, user exists with empty workgroups array

curl -X GET http://localhost:8080/api/assets/$ASSET1_ID \
  -H "Authorization: Bearer $ADMIN_TOKEN"
# Expected: 200 OK, asset exists with workgroups not containing DevOps
```

---

## Dual Ownership Validation (FR-020, Clarification 1)

**Test**: Asset with both manual creator and scan uploader

```bash
# charlie creates asset manually
DUAL_ASSET_ID=$(curl -X POST http://localhost:8080/api/assets \
  -H "Authorization: Bearer $CHARLIE_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "dual-ownership-server",
    "type": "Server",
    "owner": "charlie",
    "ip": "192.168.1.30"
  }' | jq -r '.id')

# alice uploads scan that discovers same server (merges)
curl -X POST http://localhost:8080/api/import/upload-nmap-xml \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -F "file=@testdata/nmap-dual-ownership-server.xml"

# Verify dual ownership
curl -X GET http://localhost:8080/api/assets/$DUAL_ASSET_ID \
  -H "Authorization: Bearer $ADMIN_TOKEN"

# Expected: 200 OK, returns:
# {
#   "id": $DUAL_ASSET_ID,
#   "name": "dual-ownership-server",
#   "manualCreatorId": 3,     # charlie
#   "scanUploaderId": 1,      # alice
#   ...
# }
```

**Test**: Both owners can see asset regardless of workgroups

```bash
# charlie sees asset (manual creator)
curl -X GET http://localhost:8080/api/assets \
  -H "Authorization: Bearer $CHARLIE_TOKEN" \
  | jq '.[] | select(.id == '$DUAL_ASSET_ID')'

# Expected: Asset present in list (FR-016)

# alice sees asset (scan uploader + ADMIN)
curl -X GET http://localhost:8080/api/assets \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  | jq '.[] | select(.id == '$DUAL_ASSET_ID')'

# Expected: Asset present in list
```

---

## User Deletion Ownership Handling (FR-027, Clarification 1)

**Test**: When user deleted, asset ownership becomes NULL

```bash
# Create test user
TEST_USER_ID=$(curl -X POST http://localhost:8080/api/users \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "username": "temp_user",
    "email": "temp@example.com",
    "password": "temp123",
    "roles": ["USER"]
  }' | jq -r '.id')

# temp_user creates asset
TEMP_TOKEN=$(curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"temp_user","password":"temp123"}' | jq -r '.token')

TEMP_ASSET_ID=$(curl -X POST http://localhost:8080/api/assets \
  -H "Authorization: Bearer $TEMP_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "temp-asset",
    "type": "Server",
    "owner": "temp_user"
  }' | jq -r '.id')

# Delete temp_user
curl -X DELETE http://localhost:8080/api/users/$TEST_USER_ID \
  -H "Authorization: Bearer $ADMIN_TOKEN"

# Expected: 204 No Content

# Verify asset persists with NULL manual_creator_id
curl -X GET http://localhost:8080/api/assets/$TEMP_ASSET_ID \
  -H "Authorization: Bearer $ADMIN_TOKEN"

# Expected: 200 OK, returns:
# {
#   "id": $TEMP_ASSET_ID,
#   "name": "temp-asset",
#   "manualCreatorId": null,  # Ownership reference nulled, asset persists
#   ...
# }
```

---

## Success Criteria

All tests above should pass with expected responses. This validates:
- ✅ Workgroup CRUD (Scenario 1, 7)
- ✅ User/Asset assignment (Scenario 2, 3)
- ✅ Workgroup-based filtering (Scenario 4, 5, 5a)
- ✅ Role-based access (Scenario 6, 6a)
- ✅ Dual ownership tracking (FR-020)
- ✅ User deletion handling (FR-027)
- ✅ Personal visibility rules (FR-016, Scenario 8)
- ✅ Admin-only assignment (FR-014)

---

**Execution Time**: ~10 minutes (manual)
**Automation**: Convert to Playwright E2E test (`specs/008-create-an-additional/tests/e2e/workgroup-management.spec.ts`)
