# Quickstart Guide: IP Address Mapping

**Feature**: 020-i-want-to
**For**: Developers and QA testers
**Updated**: 2025-10-15

## Overview

This guide helps you quickly set up, test, and understand the IP address mapping feature. You'll learn how to create IP mappings via UI and API, and verify that IP-based access control works correctly.

## Prerequisites

- Docker Compose environment running (`docker-compose up -d`)
- Admin user credentials (username: `admin`, password: from `.env`)
- Tool for API testing: `curl`, Postman, or HTTPie
- Browser for UI testing

## Quick Setup (5 minutes)

### 1. Start the Application

```bash
# From repository root
docker-compose up -d

# Wait for backend to be ready (~30 seconds)
docker-compose logs -f backendng | grep "Startup completed"
```

### 2. Login and Get JWT Token

```bash
# Login as admin
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username": "admin", "password": "admin123"}' \
  | jq -r '.token'

# Save token to environment variable
export JWT_TOKEN="<paste-token-here>"
```

### 3. Create Your First IP Mapping (API)

```bash
# Map single IP to user
curl -X POST http://localhost:8080/api/user-mappings \
  -H "Authorization: Bearer $JWT_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "email": "testuser@example.com",
    "ipAddress": "192.168.1.100",
    "domain": "test.example.com"
  }'

# Expected response:
# {
#   "id": 1,
#   "email": "testuser@example.com",
#   "ipAddress": "192.168.1.100",
#   "ipRangeType": "SINGLE",
#   "ipCount": 1,
#   "domain": "test.example.com",
#   "createdAt": "2025-10-15T10:00:00Z"
# }
```

### 4. Verify IP Mapping in UI

1. Open browser: `http://localhost:4321`
2. Login as admin (username: `admin`, password: `admin123`)
3. Navigate to **Admin** → **User Mappings**
4. Click the **"Manage Mappings"** tab (second tab)
5. You should see the IP mapping in the table:
   - Email: testuser@example.com
   - IP Address/Range: 192.168.1.100
   - Type: Single IP (blue badge)
   - IP Count: 1 IP
   - Domain: test.example.com
6. Use the **Edit** (pencil) or **Delete** (trash) icons to manage the mapping

---

## Creating IP Mappings (3 Formats)

### Format 1: Single IP Address

**UI**: User Mappings page → "Manage Mappings" tab → "Create New Mapping" button

```
Email Address: user@example.com
IP Address / Range: 192.168.1.100
AWS Account ID: (optional - 12 digits)
Domain: example.com (optional)
```

Click **"Create Mapping"** button to save. You'll see a success toast notification.

**API**:
```bash
curl -X POST http://localhost:8080/api/user-mappings \
  -H "Authorization: Bearer $JWT_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "email": "user@example.com",
    "ipAddress": "192.168.1.100",
    "domain": "example.com"
  }'
```

---

### Format 2: CIDR Range

**UI**: Same form as above

```
Email Address: admin@example.com
IP Address / Range: 10.0.0.0/24
AWS Account ID: (optional)
Domain: example.com (optional)
```

The system will automatically detect the CIDR notation and display a cyan **"CIDR Range"** badge in the table.

**API**:
```bash
curl -X POST http://localhost:8080/api/user-mappings \
  -H "Authorization: Bearer $JWT_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "email": "admin@example.com",
    "ipAddress": "10.0.0.0/24",
    "domain": "-NONE-"
  }'

# Response shows ipCount: 256 (10.0.0.0 - 10.0.0.255)
```

---

### Format 3: Dash Range

**UI**: Same form as above

```
Email Address: team@example.com
IP Address / Range: 172.16.0.1-172.16.0.100
AWS Account ID: (optional)
Domain: team.example.com
```

The system will automatically detect the dash range format and display a yellow **"Dash Range"** badge in the table.

**API**:
```bash
curl -X POST http://localhost:8080/api/user-mappings \
  -H "Authorization: Bearer $JWT_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "email": "team@example.com",
    "ipAddress": "172.16.0.1-172.16.0.100",
    "domain": "team.example.com"
  }'

# Response shows ipCount: 100
```

---

## Managing Mappings in UI

### Features of the "Manage Mappings" Tab

The User Mappings Management interface provides comprehensive CRUD operations:

**1. List View with Filters**
- Filter by email (press Enter or click search icon)
- Filter by domain (press Enter or click search icon)
- Paginated results (20 mappings per page)
- Shows: Email, AWS Account, IP Address/Range, Type badge, IP Count, Domain

**2. Visual Indicators**
- **Badge Colors**:
  - Blue badge: Single IP
  - Cyan badge: CIDR Range
  - Yellow badge: Dash Range
- **IP Count Formatting**:
  - `1 IP` for single addresses
  - `256 IPs` for small ranges
  - `1.5K IPs` for thousands
  - `1.2M IPs` for millions

**3. CRUD Operations**
- **Create**: Click "Create New Mapping" button → Fill form → Click "Create Mapping"
- **Edit**: Click pencil icon → Modify fields → Click "Update Mapping"
  - Note: Email field is disabled during edit (primary key)
- **Delete**: Click trash icon → Click "Confirm" in confirmation prompt
  - Safety feature: Two-click delete (first click shows Confirm/Cancel buttons)

**4. Toast Notifications**
- Success: Green toast with checkmark icon (auto-dismisses after 5 seconds)
- Error: Red toast with exclamation icon (auto-dismisses after 5 seconds)
- Manual dismiss: Click the X button on any toast

**5. Pagination Controls**
- Shows: "Showing 1 to 20 of 156 entries"
- Smart page numbers with ellipsis (... for large page counts)
- Previous/Next buttons (disabled when at boundaries)

### Example Workflow: Edit an Existing Mapping

1. Navigate to **Admin** → **User Mappings** → **"Manage Mappings"** tab
2. Use the email filter to find the mapping: `alice@example.com`
3. Click the **pencil icon** in the Actions column
4. Modify the IP address: Change `192.168.1.0/24` to `192.168.1.0/25`
5. Click **"Update Mapping"**
6. ✅ Green success toast appears: "Mapping updated successfully"
7. Table refreshes automatically, showing the updated IP count (128 IPs instead of 256)

---

## Bulk Upload via CSV/Excel

### Overview

The **"Bulk Upload"** tab provides two upload options side-by-side:
- **Excel Upload** (left card): Upload .xlsx files with user mappings
- **CSV Upload** (right card): Upload .csv files with user mappings

Both support AWS account IDs, IP addresses, and domain fields. You can mix different types of mappings in a single file.

### 1. Download Template

**UI**: User Mappings page → **"Bulk Upload"** tab → Click "Download CSV Template" or "Download Excel Template"

**API (CSV)**:
```bash
curl -X GET http://localhost:8080/api/import/user-mapping-template-csv \
  -H "Authorization: Bearer $JWT_TOKEN" \
  -o user-mapping-template.csv
```

### 2. Edit Template

**CSV Format**:
```csv
owner_email,account_id,domain
user1@example.com,123456789012,example.com
user2@example.com,,example.com
user3@example.com,987654321098,team.example.com
```

**Note**: CSV upload expects AWS account mappings (Feature 013). For IP mappings, use the "Manage Mappings" tab or API.

### 3. Upload File

**UI**:
1. User Mappings page → **"Bulk Upload"** tab
2. Select file (Excel or CSV)
3. Click **"Upload Excel"** or **"Upload CSV"** button
4. View results: Imported count, Skipped count, Error details

**API (CSV)**:
```bash
curl -X POST http://localhost:8080/api/import/upload-user-mappings-csv \
  -H "Authorization: Bearer $JWT_TOKEN" \
  -F "csvFile=@user-mappings.csv"

# Expected response:
# {
#   "message": "Successfully imported 3 user mappings",
#   "imported": 3,
#   "skipped": 0,
#   "errors": []
# }
```

---

## Testing IP-Based Access Control

### Scenario: User sees assets based on IP mapping

**Setup**:
1. Create an IP mapping: `testuser@example.com → 192.168.1.0/24`
2. Create test assets with different IPs:

```bash
# Asset 1: IP in range (should be visible)
curl -X POST http://localhost:8080/api/assets \
  -H "Authorization: Bearer $JWT_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Test Server 1",
    "type": "SERVER",
    "ip": "192.168.1.50",
    "owner": "IT"
  }'

# Asset 2: IP NOT in range (should NOT be visible)
curl -X POST http://localhost:8080/api/assets \
  -H "Authorization: Bearer $JWT_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Test Server 2",
    "type": "SERVER",
    "ip": "10.0.0.50",
    "owner": "IT"
  }'
```

**Verify** (UI):
1. Login as `testuser@example.com` (create user first if needed)
2. Navigate to **Account Vulns** page
3. ✅ You should see "Test Server 1" (192.168.1.50 is in 192.168.1.0/24)
4. ❌ You should NOT see "Test Server 2" (10.0.0.50 is NOT in range)

**Verify** (API):
```bash
# Login as testuser
TEST_TOKEN=$(curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username": "testuser", "password": "password"}' \
  | jq -r '.token')

# Fetch account vulns (includes IP-filtered assets)
curl -X GET http://localhost:8080/api/account-vulns \
  -H "Authorization: Bearer $TEST_TOKEN" | jq

# Expected: Only "Test Server 1" appears in results
```

---

## Testing Combined AWS + IP Access

### Scenario: User has both AWS account AND IP mappings

**Setup**:
```bash
# Mapping 1: AWS account
curl -X POST http://localhost:8080/api/user-mappings \
  -H "Authorization: Bearer $JWT_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "email": "hybrid@example.com",
    "awsAccountId": "123456789012",
    "domain": "aws.example.com"
  }'

# Mapping 2: IP range (same user)
curl -X POST http://localhost:8080/api/user-mappings \
  -H "Authorization: Bearer $JWT_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "email": "hybrid@example.com",
    "ipAddress": "192.168.1.0/24",
    "domain": "onprem.example.com"
  }'
```

**Create test assets**:
```bash
# Asset A: Matches AWS account
curl -X POST http://localhost:8080/api/assets \
  -H "Authorization: Bearer $JWT_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "AWS EC2 Instance",
    "type": "SERVER",
    "cloudAccountId": "123456789012",
    "ip": "10.0.0.50"
  }'

# Asset B: Matches IP range
curl -X POST http://localhost:8080/api/assets \
  -H "Authorization: Bearer $JWT_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "On-Prem Server",
    "type": "SERVER",
    "ip": "192.168.1.100"
  }'
```

**Verify**:
```bash
# Login as hybrid user
HYBRID_TOKEN=$(curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username": "hybrid", "password": "password"}' \
  | jq -r '.token')

# Fetch account vulns
curl -X GET http://localhost:8080/api/account-vulns \
  -H "Authorization: Bearer $HYBRID_TOKEN" | jq

# Expected: BOTH "AWS EC2 Instance" and "On-Prem Server" appear
```

---

## Testing Edge Cases

### Edge Case 1: Invalid IP Format

```bash
# Try to create mapping with invalid IP
curl -X POST http://localhost:8080/api/user-mappings \
  -H "Authorization: Bearer $JWT_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "email": "user@example.com",
    "ipAddress": "999.999.999.999"
  }'

# Expected: 400 Bad Request
# {
#   "error": "Validation Error",
#   "message": "Invalid IP format: must be IPv4 address (e.g., 192.168.1.100)"
# }
```

### Edge Case 2: Invalid CIDR Prefix

```bash
curl -X POST http://localhost:8080/api/user-mappings \
  -H "Authorization: Bearer $JWT_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "email": "user@example.com",
    "ipAddress": "192.168.1.0/33"
  }'

# Expected: 400 Bad Request
# {
#   "error": "Validation Error",
#   "message": "Invalid CIDR prefix: must be 0-32"
# }
```

### Edge Case 3: Invalid Dash Range (start > end)

```bash
curl -X POST http://localhost:8080/api/user-mappings \
  -H "Authorization: Bearer $JWT_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "email": "user@example.com",
    "ipAddress": "192.168.1.255-192.168.1.1"
  }'

# Expected: 400 Bad Request
# {
#   "error": "Validation Error",
#   "message": "Invalid range: start IP must be <= end IP"
# }
```

### Edge Case 4: Duplicate Mapping

```bash
# Create first mapping
curl -X POST http://localhost:8080/api/user-mappings \
  -H "Authorization: Bearer $JWT_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "email": "user@example.com",
    "ipAddress": "192.168.1.100",
    "domain": "example.com"
  }'

# Try to create duplicate
curl -X POST http://localhost:8080/api/user-mappings \
  -H "Authorization: Bearer $JWT_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "email": "user@example.com",
    "ipAddress": "192.168.1.100",
    "domain": "example.com"
  }'

# Expected: 400 Bad Request
# {
#   "error": "Conflict",
#   "message": "Mapping already exists for this email, IP address, and domain"
# }
```

---

## Sample Data for Testing

### Creating Multiple IP Mappings via API

Use this script to quickly create test data:

```bash
# Array of test mappings
declare -a MAPPINGS=(
  '{"email":"alice@example.com","ipAddress":"192.168.1.0/24","domain":"alice.example.com"}'
  '{"email":"bob@example.com","ipAddress":"10.0.0.1-10.0.0.100","domain":"bob.example.com"}'
  '{"email":"charlie@example.com","ipAddress":"172.16.0.50","domain":"charlie.example.com"}'
  '{"email":"david@example.com","ipAddress":"192.168.2.0/25","domain":"david.example.com"}'
  '{"email":"eve@example.com","ipAddress":"10.1.0.0/16","domain":"eve.example.com"}'
)

# Create each mapping
for mapping in "${MAPPINGS[@]}"; do
  curl -X POST http://localhost:8080/api/user-mappings \
    -H "Authorization: Bearer $JWT_TOKEN" \
    -H "Content-Type: application/json" \
    -d "$mapping"
  echo ""
done
```

---

## Troubleshooting

### Issue: "Admin role required" error

**Cause**: You're logged in as a non-admin user

**Solution**:
```bash
# Verify your token has ADMIN role
curl -X GET http://localhost:8080/api/auth/me \
  -H "Authorization: Bearer $JWT_TOKEN" | jq '.roles'

# Expected: ["USER", "ADMIN"]

# If not admin, login as admin user
```

### Issue: IP mapping created but assets not visible

**Cause**: Asset's IP address field may be null or empty

**Solution**:
```bash
# Check asset's IP field
curl -X GET http://localhost:8080/api/assets/{id} \
  -H "Authorization: Bearer $JWT_TOKEN" | jq '.ip'

# If null, update asset with IP address
curl -X PUT http://localhost:8080/api/assets/{id} \
  -H "Authorization: Bearer $JWT_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"ip": "192.168.1.100"}'
```

### Issue: CSV upload fails with "Empty file" error

**Cause**: CSV file has no data rows (only header)

**Solution**: Add at least one data row to CSV file

### Issue: Overlapping ranges - which user sees the asset?

**Expected Behavior**: BOTH users see the asset (most permissive approach)

**Example**:
- Mapping 1: alice@example.com → 192.168.1.0/24
- Mapping 2: bob@example.com → 192.168.1.0/25
- Asset: 192.168.1.50

Result: Both Alice and Bob see the asset in their Account Vulns view.

---

## Next Steps

- **Production Deployment**: See `docs/deployment.md`
- **API Reference**: See `contracts/*.yaml` for complete OpenAPI specs
- **Testing**: See `src/backendng/src/test/kotlin/com/secman/` for test examples
- **Architecture**: See `data-model.md` for entity design and query patterns

## Support

- **Issues**: https://github.com/your-org/secman/issues
- **Documentation**: https://docs.secman.example.com
- **Team Chat**: #secman-dev on Slack
