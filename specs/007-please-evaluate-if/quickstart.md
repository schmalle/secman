# Quickstart: Dependency Update Validation

**Feature**: 007-please-evaluate-if - Backend Dependency Evaluation and Update
**Purpose**: Verify that all dependency updates work correctly and the application remains stable

## Prerequisites

- Docker and Docker Compose installed
- Gradle 8.x installed
- Java 21 JDK installed
- Git repository at a clean state

## Quick Validation (5 minutes)

### 1. Build Verification

```bash
cd /Users/flake/sources/misc/secman/src/backendng
./gradlew clean build
```

**Expected**: Build succeeds with no errors
**If fails**: Check build output for compilation errors or dependency conflicts

### 2. Test Execution

```bash
./gradlew test
```

**Expected**: All tests pass with ≥80% coverage
**If fails**: Review test output, fix breaking changes

### 3. Shadow JAR Creation

```bash
./gradlew shadowJar
ls -lh build/libs/
```

**Expected**: Shadow JAR created successfully (~80-100MB)
**If fails**: Check Shadow plugin configuration

### 4. Docker Build (AMD64)

```bash
cd /Users/flake/sources/misc/secman
docker-compose build backend
```

**Expected**: Docker image builds successfully
**If fails**: Check Dockerfile and dependency downloads

### 5. Application Startup

```bash
docker-compose up -d
docker-compose logs -f backend | head -50
```

**Expected**:
- Backend starts within 30 seconds
- No error messages in logs
- Health endpoint responds

**Verify health**:
```bash
curl http://localhost:8080/health
```

Expected response: `{"status":"UP"}`

## Comprehensive Validation (30 minutes)

### 1. Authentication Flow

```bash
# Login with test user
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"testuser","password":"password"}'
```

**Expected**: JWT token returned
**Validates**: Spring Security Crypto update (password encoding)

### 2. Excel Import - Requirements

```bash
# Prepare test Excel file
cp /path/to/test/requirements.xlsx /tmp/test-req.xlsx

# Upload
curl -X POST http://localhost:8080/api/import/upload-xlsx \
  -H "Authorization: Bearer YOUR_TOKEN" \
  -F "file=@/tmp/test-req.xlsx"
```

**Expected**: Import succeeds, returns count
**Validates**: Apache POI 5.4.1 update

### 3. Excel Import - Vulnerabilities

```bash
curl -X POST http://localhost:8080/api/import/upload-vulnerability-xlsx \
  -H "Authorization: Bearer YOUR_TOKEN" \
  -F "file=@/tmp/test-vulns.xlsx"
```

**Expected**: Import succeeds
**Validates**: Apache POI Excel parsing

### 4. XML Import - Nmap

```bash
curl -X POST http://localhost:8080/api/import/upload-nmap-xml \
  -H "Authorization: Bearer YOUR_TOKEN" \
  -F "file=@/tmp/test-nmap.xml"
```

**Expected**: Import succeeds, assets created
**Validates**: XML parsing (POI scratchpad)

### 5. XML Import - Masscan

```bash
curl -X POST http://localhost:8080/api/import/upload-masscan-xml \
  -H "Authorization: Bearer YOUR_TOKEN" \
  -F "file=@/tmp/test-masscan.xml"
```

**Expected**: Import succeeds
**Validates**: Masscan parser service

### 6. API Performance Check

```bash
# Run 100 requests and measure p95
for i in {1..100}; do
  curl -w "%{time_total}\n" -o /dev/null -s \
    -H "Authorization: Bearer YOUR_TOKEN" \
    http://localhost:8080/api/assets
done | sort -n | tail -5
```

**Expected**: p95 < 200ms
**Validates**: No performance regression from Micronaut/Netty updates

### 7. Database Connectivity

```bash
docker-compose exec backend sh -c \
  'echo "SELECT VERSION();" | mysql -h mariadb -u secman -psecman secmandb'
```

**Expected**: MariaDB version displayed
**Validates**: MariaDB JDBC 3.5.3 compatibility

### 8. OAuth2 Flow (if configured)

```bash
# Test OAuth2 redirect
curl -I http://localhost:8080/oauth/login/google
```

**Expected**: 302 redirect to OAuth provider
**Validates**: Micronaut Security OAuth2

### 9. Email Functionality (if SMTP configured)

```bash
# Trigger password reset email
curl -X POST http://localhost:8080/api/auth/forgot-password \
  -H "Content-Type: application/json" \
  -d '{"email":"test@example.com"}'
```

**Expected**: Email sent (check logs or mailbox)
**Validates**: Micronaut Email + jsoup HTML processing

### 10. Multi-Arch Docker Build

```bash
# Build for ARM64 (if on ARM Mac) or AMD64
docker buildx build --platform linux/amd64,linux/arm64 \
  -t secman-backend:test \
  -f src/backendng/Dockerfile .
```

**Expected**: Both architectures build successfully
**Validates**: Shadow JAR multi-arch compatibility

## Regression Testing

### Critical User Flows

1. **Login → View Assets → View Vulnerabilities**
   ```bash
   # Login
   TOKEN=$(curl -X POST http://localhost:8080/api/auth/login \
     -H "Content-Type: application/json" \
     -d '{"username":"admin","password":"admin"}' | jq -r '.token')

   # List assets
   curl -H "Authorization: Bearer $TOKEN" \
     http://localhost:8080/api/assets | jq '.[] | .name'

   # View vulnerabilities
   curl -H "Authorization: Bearer $TOKEN" \
     http://localhost:8080/api/vulnerabilities/current | jq 'length'
   ```

2. **Admin Role Check**
   ```bash
   # Try admin endpoint with normal user
   curl -H "Authorization: Bearer $USER_TOKEN" \
     http://localhost:8080/api/vulnerability-exceptions

   # Expected: 403 Forbidden
   ```

3. **RBAC Verification**
   ```bash
   # VULN role access
   curl -H "Authorization: Bearer $VULN_TOKEN" \
     http://localhost:8080/api/vulnerability-exceptions

   # Expected: 200 OK with data
   ```

## Performance Baseline

Record these metrics for comparison:

| Metric | Baseline | After Update | Delta |
|--------|----------|--------------|-------|
| Build time | ___ sec | ___ sec | ___ |
| Test execution | ___ sec | ___ sec | ___ |
| Docker build | ___ sec | ___ sec | ___ |
| Startup time | ___ sec | ___ sec | ___ |
| /api/assets (p95) | ___ ms | ___ ms | ___ |
| /api/vulnerabilities (p95) | ___ ms | ___ ms | ___ |
| Shadow JAR size | ___ MB | ___ MB | ___ |

## Rollback Procedure

If validation fails:

```bash
# Stop containers
docker-compose down

# Revert build.gradle.kts changes
git checkout HEAD -- src/backendng/build.gradle.kts

# Rebuild
cd src/backendng && ./gradlew clean build

# Restart
docker-compose up -d
```

## Success Checklist

- [ ] Build completes without errors
- [ ] All unit tests pass
- [ ] Shadow JAR created successfully
- [ ] Docker image builds (AMD64 + ARM64)
- [ ] Application starts < 30 seconds
- [ ] Health endpoint responds
- [ ] Authentication works (password encoding)
- [ ] Excel import works (Requirements, Vulnerabilities)
- [ ] XML import works (Nmap, Masscan)
- [ ] API performance < 200ms p95
- [ ] Database connectivity verified
- [ ] OAuth2 flow works (if configured)
- [ ] Email sending works (if configured)
- [ ] RBAC permissions enforced
- [ ] No deprecation warnings in logs
- [ ] No security vulnerabilities detected

## Known Issues & Workarounds

### Issue: Kotlin Compiler Warnings
**Workaround**: Document warnings, verify they don't affect runtime

### Issue: Shadow Plugin Relocation
**Workaround**: Update plugin ID in build.gradle.kts before building

### Issue: Micronaut Netty Virtual Threads
**Workaround**: Disable experimental feature if issues arise

## Next Steps

After successful validation:
1. Commit changes with conventional commit message
2. Create PR with test results
3. Update CLAUDE.md with new dependency versions
4. Document any breaking changes in changelog
5. Deploy to staging environment
6. Monitor for 24 hours before production

---

**Estimated Total Time**: 30-45 minutes for comprehensive validation
**Critical Path**: Build → Test → Docker → Startup → API validation
