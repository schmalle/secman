# Deployment Checklist: Vulnerability Overdue & Exception Logic

**Feature:** 021-vulnerability-overdue-exception-logic  
**Version:** 1.0  
**Target Environment:** Production

---

## Pre-Deployment Checklist

### Code Review ✅
- [ ] All pull requests approved and merged
- [ ] Code follows style guidelines
- [ ] No commented-out code or debug statements
- [ ] All TODOs resolved or tracked
- [ ] Lint checks passing

### Testing ✅
- [ ] Unit tests written and passing
- [ ] Integration tests passing
- [ ] E2E tests created (manual verification if backend not running)
- [ ] Manual testing completed
- [ ] No critical bugs identified

### Documentation ✅
- [ ] Admin guide complete
- [ ] User guide complete
- [ ] API documentation updated
- [ ] CHANGELOG updated
- [ ] README updated (if needed)

### Database ✅
- [ ] Migration scripts created and tested
- [ ] Rollback scripts prepared
- [ ] Database backup plan confirmed
- [ ] Index creation statements reviewed

### Security ✅
- [ ] ADMIN role enforcement verified
- [ ] Input validation complete
- [ ] No SQL injection vulnerabilities
- [ ] No XSS vulnerabilities
- [ ] CSRF protection in place

---

## Deployment Steps

### Phase 1: Database Migration (Estimated: 5-10 minutes)

#### Step 1.1: Backup Database
```bash
# Create backup of production database
pg_dump -h <host> -U <user> secman > secman_backup_$(date +%Y%m%d_%H%M%S).sql

# Verify backup
ls -lh secman_backup_*.sql
```
✅ **Verification:** Backup file exists and has reasonable size

#### Step 1.2: Run Migrations
```bash
# Navigate to backend directory
cd src/backendng

# Run Flyway migration
./gradlew flywayMigrate -Dflyway.url=<jdbc_url> -Dflyway.user=<user> -Dflyway.password=<password>

# Or use Flyway CLI
flyway migrate -url=<jdbc_url> -user=<user> -password=<password>
```
✅ **Verification:** 
```bash
# Check migration status
flyway info -url=<jdbc_url> -user=<user> -password=<password>
```
Expected output should show:
- V1_XX__add_vulnerability_config.sql - SUCCESS
- V1_YY__add_asset_exception.sql - SUCCESS (if Phase 2 included)

#### Step 1.3: Verify Database Changes
```sql
-- Connect to database
psql -h <host> -U <user> -d secman

-- Verify vulnerability_config table exists
\d vulnerability_config

-- Expected columns:
-- id, reminder_one_days, updated_by, created_at, updated_at

-- Check default config created
SELECT * FROM vulnerability_config;

-- Expected: 1 row with reminder_one_days = 30

-- Verify vulnerability_exception enhancements (if Phase 2)
\d vulnerability_exception

-- Expected new columns:
-- asset_id (nullable, foreign key)

-- Exit psql
\q
```
✅ **Verification:** All expected tables and columns exist, default config present

---

### Phase 2: Backend Deployment (Estimated: 10-15 minutes)

#### Step 2.1: Build Backend
```bash
cd src/backendng

# Clean and build
./gradlew clean build

# Verify build success
ls -lh build/libs/secman-*.jar
```
✅ **Verification:** JAR file created successfully

#### Step 2.2: Stop Current Backend
```bash
# Stop existing service (method depends on deployment)
# Option A: Docker
docker-compose down backend

# Option B: systemd
sudo systemctl stop secman-backend

# Option C: PM2
pm2 stop secman-backend

# Option D: Manual kill
ps aux | grep secman
kill <PID>
```
✅ **Verification:** 
```bash
# Verify process stopped
ps aux | grep secman
# Should return no running processes
```

#### Step 2.3: Deploy New Backend
```bash
# Copy new JAR to deployment location
cp build/libs/secman-*.jar /opt/secman/

# Start service
# Option A: Docker
docker-compose up -d backend

# Option B: systemd
sudo systemctl start secman-backend

# Option C: PM2
pm2 start secman-backend

# Option D: Manual
cd /opt/secman
java -jar secman-*.jar &
```
✅ **Verification:**
```bash
# Check service status
# Docker:
docker-compose ps backend

# systemd:
sudo systemctl status secman-backend

# PM2:
pm2 status secman-backend

# Manual:
ps aux | grep secman
```

#### Step 2.4: Verify Backend Health
```bash
# Wait for startup (usually 30-60 seconds)
sleep 60

# Check health endpoint
curl http://localhost:8080/health

# Expected response: {"status":"UP"}

# Check vulnerability config endpoint (requires auth)
curl -u admin:admin http://localhost:8080/api/admin/vulnerability-config

# Expected: JSON with reminderOneDays: 30
```
✅ **Verification:** Backend is running and API responds correctly

---

### Phase 3: Frontend Deployment (Estimated: 5-10 minutes)

#### Step 3.1: Build Frontend
```bash
cd src/frontend

# Install dependencies (if needed)
npm ci

# Build for production
npm run build

# Verify build
ls -lh dist/
```
✅ **Verification:** dist/ directory contains built files

#### Step 3.2: Deploy Frontend
```bash
# Option A: Copy to web server
rsync -avz dist/ /var/www/secman/

# Option B: Deploy to CDN/S3
aws s3 sync dist/ s3://secman-frontend/

# Option C: Docker
docker-compose up -d frontend

# Option D: Nginx
sudo cp -r dist/* /usr/share/nginx/html/secman/
sudo nginx -s reload
```
✅ **Verification:**
```bash
# Check files deployed
ls -lh /var/www/secman/ # or appropriate path

# Check web server
curl http://localhost:3000/

# Should return HTML
```

#### Step 3.3: Verify Frontend Access
```bash
# Open in browser or curl
curl http://localhost:3000/admin/vulnerability-config

# Should return HTML with "Vulnerability Settings"

# Test API proxy (if using)
curl http://localhost:3000/api/health

# Should proxy to backend
```
✅ **Verification:** Frontend loads correctly and proxies API calls

---

### Phase 4: Smoke Testing (Estimated: 10-15 minutes)

#### Test 4.1: Admin Login
- [ ] Navigate to login page
- [ ] Log in as ADMIN user
- [ ] Verify successful login

#### Test 4.2: Access Configuration
- [ ] Navigate to Admin panel
- [ ] Click "Vulnerability Settings"
- [ ] Verify page loads with two tabs
- [ ] Check "Threshold Settings" tab loads
- [ ] Check "Exception Management" tab loads

#### Test 4.3: View Threshold
- [ ] On Threshold Settings tab
- [ ] Verify "Reminder One" input shows 30 days
- [ ] Verify help text is visible
- [ ] Verify "Last updated by" shows (may be null initially)

#### Test 4.4: Update Threshold
- [ ] Change value to 45
- [ ] Click "Save Configuration"
- [ ] Verify success message appears
- [ ] Refresh page
- [ ] Verify value persists as 45
- [ ] Change back to 30 for consistency

#### Test 4.5: View Vulnerabilities with Status
- [ ] Navigate to Vulnerabilities → Current
- [ ] Verify "Overdue Status" column exists
- [ ] Verify at least one badge visible (OK/OVERDUE/EXCEPTED)
- [ ] Hover over a badge
- [ ] Verify tooltip shows details

#### Test 4.6: Create Exception (IP)
- [ ] Navigate to Admin → Vulnerability Settings → Exceptions
- [ ] Click "Create Exception"
- [ ] Select IP type
- [ ] Enter IP: 192.168.1.200
- [ ] Set expiration 30 days from now
- [ ] Enter reason: "Test exception for deployment verification"
- [ ] Click Create (or Preview & Create)
- [ ] Verify exception appears in table
- [ ] Verify "Affected" count shows

#### Test 4.7: Edit Exception
- [ ] Find test exception (192.168.1.200)
- [ ] Click Edit button
- [ ] Change reason to "Updated during deployment verification"
- [ ] Click Save
- [ ] Verify updated reason appears

#### Test 4.8: Delete Exception
- [ ] Find test exception
- [ ] Click Delete button
- [ ] Confirm deletion in dialog
- [ ] Verify exception removed from table

✅ **Verification:** All smoke tests passed

---

### Phase 5: Performance Check (Estimated: 5 minutes)

#### Test 5.1: Page Load Times
```bash
# Test vulnerability page load time
time curl -s http://localhost:3000/vulnerabilities/current > /dev/null

# Should complete in < 2 seconds

# Test API response time
time curl -u admin:admin http://localhost:8080/api/vulnerabilities/current?page=0&size=50 > /dev/null

# Should complete in < 1 second (with small dataset)
```
✅ **Verification:** Response times acceptable

#### Test 5.2: Database Query Performance
```sql
-- Check overdue calculation query performance
EXPLAIN ANALYZE 
SELECT v.*, 
       EXTRACT(DAY FROM (CURRENT_TIMESTAMP - v.scan_timestamp)) as age_days
FROM vulnerability v
WHERE EXTRACT(DAY FROM (CURRENT_TIMESTAMP - v.scan_timestamp)) > 
      (SELECT reminder_one_days FROM vulnerability_config LIMIT 1);

-- Execution time should be < 100ms
```
✅ **Verification:** Query executes quickly

---

### Phase 6: User Acceptance (Optional, Estimated: 30 minutes)

#### UAT 6.1: Admin User Testing
- [ ] Have admin user test threshold configuration
- [ ] Verify they understand the interface
- [ ] Collect feedback on usability

#### UAT 6.2: Regular User Testing
- [ ] Have regular user view vulnerabilities
- [ ] Verify they understand overdue badges
- [ ] Verify filtering works intuitively

#### UAT 6.3: Exception Workflow
- [ ] Have admin user create real exception
- [ ] Verify affected vulnerabilities change status
- [ ] Confirm exception management is intuitive

✅ **Verification:** Users satisfied with functionality

---

## Post-Deployment Verification

### Final Checks ✅
- [ ] All deployment steps completed successfully
- [ ] Smoke tests passed
- [ ] Performance is acceptable
- [ ] No errors in logs
- [ ] Users can access all features
- [ ] Documentation distributed to users

### Monitoring Setup ✅
- [ ] Application logs being captured
- [ ] Error rates being monitored
- [ ] Response times being tracked
- [ ] Database performance being monitored

---

## Rollback Procedure

If critical issues are discovered, follow this rollback procedure:

### Step 1: Stop Services
```bash
# Stop backend
docker-compose down backend
# OR
sudo systemctl stop secman-backend

# Stop frontend (if separate)
docker-compose down frontend
```

### Step 2: Restore Previous Version
```bash
# Backend
cd /opt/secman
mv secman-current.jar secman-failed.jar
mv secman-previous.jar secman-current.jar

# Frontend
cd /var/www/secman
rm -rf *
cp -r /backups/frontend-previous/* .
```

### Step 3: Rollback Database
```sql
-- If database changes need rollback (rare)
-- Restore from backup
psql -h <host> -U <user> -d secman < secman_backup_<timestamp>.sql

-- OR manually remove tables
DROP TABLE IF EXISTS vulnerability_config CASCADE;
-- (Only if completely broken)
```

### Step 4: Restart Services
```bash
# Start backend
docker-compose up -d backend

# Start frontend
docker-compose up -d frontend
```

### Step 5: Verify Rollback
```bash
# Check health
curl http://localhost:8080/health

# Verify old version running
curl http://localhost:8080/api/version
```

---

## Post-Deployment Tasks

### Immediate (Within 24 hours)
- [ ] Monitor error logs for any issues
- [ ] Check user feedback channels
- [ ] Verify no performance degradation
- [ ] Update deployment documentation with any lessons learned

### Short-term (Within 1 week)
- [ ] Collect user feedback
- [ ] Monitor exception usage
- [ ] Track overdue vulnerability counts
- [ ] Review any issues or questions

### Long-term (Within 1 month)
- [ ] Analyze overdue trends
- [ ] Review exception patterns
- [ ] Consider threshold adjustments
- [ ] Plan any needed improvements

---

## Troubleshooting Common Issues

### Issue: Migration fails
**Solution:**
1. Check database connectivity
2. Verify user has permission to ALTER tables
3. Check for conflicting migration versions
4. Review Flyway logs

### Issue: Backend won't start
**Solution:**
1. Check logs: `docker-compose logs backend` or `journalctl -u secman-backend`
2. Verify database connection string
3. Check port 8080 not already in use
4. Verify JAR file is not corrupted

### Issue: Frontend can't connect to backend
**Solution:**
1. Verify backend is running: `curl http://localhost:8080/health`
2. Check proxy configuration in Astro/Vite config
3. Verify CORS settings if separate domains
4. Check network/firewall rules

### Issue: Config page returns 403
**Solution:**
1. Verify user has ADMIN role
2. Check authentication is working
3. Review backend logs for auth errors
4. Verify session/JWT is valid

### Issue: Overdue badges not showing
**Solution:**
1. Clear browser cache
2. Check browser console for JavaScript errors
3. Verify API returns overdue status fields
4. Check that OverdueStatusBadge component is imported

---

## Success Criteria

Deployment is considered successful when:

✅ All smoke tests pass  
✅ No critical errors in logs  
✅ Users can access all features  
✅ Performance is acceptable  
✅ Configuration persists correctly  
✅ Exceptions work as expected  
✅ Overdue status displays correctly  

---

## Contact Information

**For Deployment Issues:**
- DevOps Team: devops@example.com
- On-call: +1-555-0100

**For Feature Questions:**
- Product Owner: product@example.com
- Security Team: security@example.com

---

**Deployment Date:** ___________  
**Deployed By:** ___________  
**Verified By:** ___________  
**Approval:** ___________

---

**Last Updated:** 2025-10-16  
**Version:** 1.0  
**Feature:** 021-vulnerability-overdue-exception-logic
