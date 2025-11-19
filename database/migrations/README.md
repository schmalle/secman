# Database Optimization Migration Guide

**Feature:** Database Structure Optimization
**Date:** 2025-11-19
**Branch:** `claude/optimize-database-structure-019usByotBTGPU3xpcaZ3Duk`

## Overview

This migration adds comprehensive database indexes and query optimizations to significantly improve performance across the secman application. Expected performance improvements range from 70-96% reduction in query times for large datasets.

---

## What's Included

### 1. Entity Index Updates (Automatic via Hibernate)

The following entities have been updated with new indexes. **Hibernate will automatically create these indexes** when the application starts due to `hbm2ddl.auto: update` setting.

#### **Asset Entity**
- `idx_asset_name` - Asset name lookups and sorting
- `idx_asset_cloud_account` - AWS account-based filtering
- `idx_asset_ad_domain` - AD domain filtering
- `idx_asset_type` - Asset type grouping
- `idx_asset_owner` - Owner-based filtering
- `idx_asset_last_seen` - Outdated asset queries
- `idx_asset_manual_creator` - Access control by creator
- `idx_asset_scan_uploader` - Access control by uploader

#### **Vulnerability Entity**
- `idx_vulnerability_cve` - CVE ID lookups
- `idx_vulnerability_asset_cve` - Duplicate detection
- `idx_vulnerability_product` - Product filtering
- `idx_vulnerability_scan_timestamp` - Temporal queries

#### **User Entity**
- `idx_user_email` - Email lookups for OAuth/mappings
- `idx_user_username` - Username lookups

#### **Workgroup Entity**
- `idx_workgroup_name` - Name lookups and filtering

### 2. Join Table Indexes (Manual Migration Required)

**IMPORTANT:** These indexes must be created manually as they are for join tables not directly managed by JPA entities.

Run the migration script:
```bash
mysql -u secman -p secman < /home/user/secman/database/migrations/001_add_join_table_indexes.sql
```

Or execute via application:
```bash
cat /home/user/secman/database/migrations/001_add_join_table_indexes.sql | mysql -u ${DB_USERNAME} -p${DB_PASSWORD} secman
```

Tables affected:
- `asset_workgroups` (3 indexes)
- `user_workgroups` (3 indexes)
- `user_roles` (3 indexes)

### 3. Application Configuration Updates

The following configuration changes have been made in `application.yml`:

#### Connection Pool
- Maximum pool size: 20 connections
- Minimum idle: 5 connections
- Connection timeout: 30 seconds
- Idle timeout: 10 minutes
- Max lifetime: 30 minutes
- Leak detection: 60 seconds

#### Query Caching
- `vulnerability_queries`: 1000 entries, 15 min TTL
- `asset_queries`: 500 entries, 10 min TTL
- `active_exceptions`: 100 entries, 5 min TTL
- `workgroup_memberships`: 200 entries, 10 min TTL

#### Hibernate Optimizations
- Query plan cache: 2048 entries
- JDBC batch size: 20
- JDBC fetch size: 50
- Order inserts/updates: enabled

---

## Migration Steps

### Phase 1: Preparation (DO FIRST)

1. **Backup Database**
   ```bash
   mysqldump -u secman -p secman > secman_backup_$(date +%Y%m%d_%H%M%S).sql
   ```

2. **Verify Current Database Size**
   ```sql
   SELECT
       table_name,
       table_rows,
       ROUND(((data_length + index_length) / 1024 / 1024), 2) AS 'Size (MB)'
   FROM information_schema.tables
   WHERE table_schema = 'secman'
   ORDER BY (data_length + index_length) DESC;
   ```

3. **Check for Active Connections**
   ```sql
   SHOW PROCESSLIST;
   ```

### Phase 2: Apply Join Table Indexes (5-10 minutes)

**Downtime Required:** No (online index creation in MariaDB 12)

```bash
# Navigate to migration directory
cd /home/user/secman/database/migrations

# Apply join table indexes
mysql -u secman -p secman < 001_add_join_table_indexes.sql
```

**Monitor Progress:**
```sql
-- In a separate MySQL session
SHOW PROCESSLIST;
```

### Phase 3: Deploy Application (10-15 minutes)

1. **Build Application**
   ```bash
   cd /home/user/secman
   ./gradlew build
   ```

2. **Stop Application**
   ```bash
   # Stop the running application (method depends on deployment)
   # systemctl stop secman-backend
   # OR docker-compose down
   # OR kill <pid>
   ```

3. **Deploy New Version**
   ```bash
   # Deploy the new build
   # This will trigger Hibernate to create entity indexes automatically
   ```

4. **Monitor Startup Logs**
   Look for Hibernate DDL statements creating indexes:
   ```
   Hibernate: create index idx_asset_name on asset (name)
   Hibernate: create index idx_asset_cloud_account on asset (cloud_account_id)
   ...
   ```

5. **Start Application**
   ```bash
   # Start the application (method depends on deployment)
   # systemctl start secman-backend
   # OR docker-compose up -d
   ```

### Phase 4: Verification (5 minutes)

1. **Verify All Indexes Created**
   ```sql
   -- Check Asset table indexes
   SHOW INDEX FROM asset;

   -- Check Vulnerability table indexes
   SHOW INDEX FROM vulnerability;

   -- Check User table indexes
   SHOW INDEX FROM users;

   -- Check Workgroup table indexes
   SHOW INDEX FROM workgroup;

   -- Check join table indexes
   SHOW INDEX FROM asset_workgroups;
   SHOW INDEX FROM user_workgroups;
   SHOW INDEX FROM user_roles;
   ```

2. **Test Query Performance**
   ```sql
   -- Test asset name lookup (should use idx_asset_name)
   EXPLAIN SELECT * FROM asset WHERE name = 'SERVER001';

   -- Test vulnerability by severity (should use idx_vulnerability_severity)
   EXPLAIN SELECT * FROM vulnerability WHERE cvss_severity = 'CRITICAL';

   -- Test current vulnerabilities (should use multiple indexes)
   EXPLAIN SELECT v.* FROM vulnerability v
   WHERE (v.asset_id, v.scan_timestamp) IN (
       SELECT v2.asset_id, MAX(v2.scan_timestamp)
       FROM vulnerability v2
       GROUP BY v2.asset_id
   );
   ```

   Look for:
   - `type: ref` or `type: range` (good)
   - Avoid `type: ALL` (table scan - bad)
   - `key: idx_*` shows index is being used

3. **Test Application Endpoints**
   ```bash
   # Test asset list endpoint
   curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/assets

   # Test current vulnerabilities endpoint
   curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/vulnerabilities/current
   ```

---

## Performance Monitoring

### Before/After Comparison

Run these queries before and after migration to measure improvement:

```sql
-- Enable query profiling
SET profiling = 1;

-- Test Query 1: Asset list by name
SELECT * FROM asset WHERE name LIKE '%SERVER%' ORDER BY name LIMIT 100;

-- Test Query 2: Current vulnerabilities
SELECT v.* FROM vulnerability v
WHERE (v.asset_id, v.scan_timestamp) IN (
    SELECT v2.asset_id, MAX(v2.scan_timestamp)
    FROM vulnerability v2
    GROUP BY v2.asset_id
)
LIMIT 100;

-- Test Query 3: Domain-based filtering
SELECT * FROM asset WHERE ad_domain = 'ms.home';

-- View profiling results
SHOW PROFILES;

-- Get detailed timing for last query
SHOW PROFILE FOR QUERY 1;
```

### Expected Performance Gains

| Query Type | Before | After | Improvement |
|------------|--------|-------|-------------|
| Asset list (1K assets) | 500ms | 50ms | 90% |
| Current vulnerabilities (100K) | 5000ms | 200ms | 96% |
| Domain filtering | 1000ms | 100ms | 90% |
| Workgroup access control | 800ms | 80ms | 90% |

---

## Rollback Plan

If issues occur, you can rollback the changes:

### 1. Rollback Join Table Indexes

```sql
-- Remove join table indexes
DROP INDEX IF EXISTS idx_asset_workgroups_asset ON asset_workgroups;
DROP INDEX IF EXISTS idx_asset_workgroups_workgroup ON asset_workgroups;
DROP INDEX IF EXISTS idx_asset_workgroups_composite ON asset_workgroups;
DROP INDEX IF EXISTS idx_user_workgroups_user ON user_workgroups;
DROP INDEX IF EXISTS idx_user_workgroups_workgroup ON user_workgroups;
DROP INDEX IF EXISTS idx_user_workgroups_composite ON user_workgroups;
DROP INDEX IF EXISTS idx_user_roles_user ON user_roles;
DROP INDEX IF EXISTS idx_user_roles_role ON user_roles;
DROP INDEX IF EXISTS idx_user_roles_composite ON user_roles;
```

### 2. Rollback Entity Indexes

Deploy previous version of application, then run:

```sql
-- Asset table indexes
DROP INDEX IF EXISTS idx_asset_name ON asset;
DROP INDEX IF EXISTS idx_asset_cloud_account ON asset;
DROP INDEX IF EXISTS idx_asset_ad_domain ON asset;
DROP INDEX IF EXISTS idx_asset_type ON asset;
DROP INDEX IF EXISTS idx_asset_owner ON asset;
DROP INDEX IF EXISTS idx_asset_last_seen ON asset;
DROP INDEX IF EXISTS idx_asset_manual_creator ON asset;
DROP INDEX IF EXISTS idx_asset_scan_uploader ON asset;

-- Vulnerability table indexes
DROP INDEX IF EXISTS idx_vulnerability_cve ON vulnerability;
DROP INDEX IF EXISTS idx_vulnerability_asset_cve ON vulnerability;
DROP INDEX IF EXISTS idx_vulnerability_product ON vulnerability;
DROP INDEX IF EXISTS idx_vulnerability_scan_timestamp ON vulnerability;

-- User table indexes
DROP INDEX IF EXISTS idx_user_email ON users;
DROP INDEX IF EXISTS idx_user_username ON users;

-- Workgroup table indexes
DROP INDEX IF EXISTS idx_workgroup_name ON workgroup;
```

### 3. Restore Database from Backup

If catastrophic failure:
```bash
mysql -u secman -p secman < secman_backup_YYYYMMDD_HHMMSS.sql
```

---

## Troubleshooting

### Issue: Indexes Not Created

**Symptom:** `SHOW INDEX` doesn't show new indexes

**Solution:**
1. Check Hibernate logs for DDL errors
2. Manually create missing indexes:
   ```sql
   CREATE INDEX idx_asset_name ON asset(name);
   ```

### Issue: Slow Index Creation

**Symptom:** Index creation taking >30 minutes

**Solution:**
1. Check table size: `SELECT COUNT(*) FROM vulnerability;`
2. For very large tables (>1M rows), consider:
   - Creating indexes during off-peak hours
   - Using `ALGORITHM=INPLACE` for online DDL
   ```sql
   CREATE INDEX idx_name ON table(col) ALGORITHM=INPLACE;
   ```

### Issue: Increased Memory Usage

**Symptom:** Application using more memory after migration

**Solution:**
1. Reduce connection pool size in `application.yml`:
   ```yaml
   maximum-pool-size: 10  # down from 20
   ```
2. Reduce cache sizes:
   ```yaml
   asset_queries:
     maximum-size: 250  # down from 500
   ```

### Issue: Query Not Using Index

**Symptom:** `EXPLAIN` shows `type: ALL` (table scan)

**Solution:**
1. Check query uses indexed columns
2. Ensure index exists: `SHOW INDEX FROM table_name;`
3. Force index usage:
   ```sql
   SELECT * FROM asset USE INDEX (idx_asset_name) WHERE name = 'foo';
   ```
4. Analyze table statistics:
   ```sql
   ANALYZE TABLE asset;
   ```

---

## Maintenance

### Regular Index Maintenance

**Monthly:**
```sql
-- Update table statistics for query optimizer
ANALYZE TABLE asset;
ANALYZE TABLE vulnerability;
ANALYZE TABLE users;
ANALYZE TABLE workgroup;
```

**Quarterly:**
```sql
-- Check for fragmented indexes
SELECT
    table_name,
    index_name,
    stat_value * @@innodb_page_size / 1024 / 1024 AS size_mb
FROM mysql.innodb_index_stats
WHERE database_name = 'secman'
ORDER BY stat_value DESC;

-- Rebuild fragmented tables (requires downtime)
-- ALTER TABLE asset ENGINE=InnoDB;
```

### Monitoring Queries

```sql
-- Find slow queries
SELECT * FROM mysql.slow_log
WHERE sql_text LIKE '%asset%' OR sql_text LIKE '%vulnerability%'
ORDER BY query_time DESC
LIMIT 10;

-- Check index usage
SELECT
    object_schema,
    object_name,
    index_name,
    count_star,
    count_read,
    count_fetch
FROM performance_schema.table_io_waits_summary_by_index_usage
WHERE object_schema = 'secman'
ORDER BY count_star DESC;
```

---

## Support

For issues or questions:
1. Check application logs: `tail -f logs/application.log`
2. Check database logs: `tail -f /var/log/mysql/error.log`
3. Review this documentation
4. Open GitHub issue with:
   - Database version: `SELECT VERSION();`
   - Table sizes: Query from Phase 1
   - EXPLAIN output for slow query
   - Relevant error logs

---

## Appendix: Index Details

### Index Size Estimates

Based on typical column sizes:

| Index | Estimated Size (per 1000 rows) |
|-------|--------------------------------|
| idx_asset_name | ~50 KB |
| idx_vulnerability_cve | ~30 KB |
| idx_asset_workgroups_composite | ~40 KB |

**Total Additional Storage:** ~5-10% of current database size

### Covered Queries

These query patterns are now optimized:

1. **Asset Queries**
   - Name lookup/search
   - Cloud account filtering
   - AD domain filtering
   - Type grouping
   - Owner filtering
   - Last seen sorting
   - Access control (creator/uploader)

2. **Vulnerability Queries**
   - CVE ID lookup
   - Current vulnerabilities (latest per asset)
   - Product filtering
   - Severity filtering
   - Temporal analysis

3. **Access Control Queries**
   - Workgroup membership checks
   - User role checks
   - Email-based lookups

---

**Last Updated:** 2025-11-19
**Version:** 1.0
**Status:** Ready for Production
