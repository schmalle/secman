-- Migration Validation Script
-- This script validates the demand-based risk assessment migration
-- Run this after the migration to ensure data integrity

-- Check 1: Verify all risk assessments have either demand_id or asset_id (legacy)
SELECT 
    '1. Risk Assessments without Demand or Asset Reference' as check_name,
    COUNT(*) as count,
    CASE 
        WHEN COUNT(*) = 0 THEN 'PASS' 
        ELSE 'FAIL' 
    END as status
FROM risk_assessment 
WHERE demand_id IS NULL AND asset_id IS NULL;

-- Check 2: Verify all demands have proper asset information
SELECT 
    '2. Demands without Asset Information' as check_name,
    COUNT(*) as count,
    CASE 
        WHEN COUNT(*) = 0 THEN 'PASS' 
        ELSE 'FAIL' 
    END as status
FROM demand 
WHERE (demand_type = 'CHANGE' AND existing_asset_id IS NULL) 
   OR (demand_type = 'CREATE_NEW' AND (new_asset_name IS NULL OR new_asset_type IS NULL OR new_asset_owner IS NULL));

-- Check 3: Verify migration demands are properly linked
SELECT 
    '3. Migration Demands without Risk Assessments' as check_name,
    COUNT(*) as count,
    CASE 
        WHEN COUNT(*) = 0 THEN 'PASS' 
        ELSE 'WARN' 
    END as status
FROM demand d
WHERE d.title LIKE 'Migration Demand for Asset:%'
AND NOT EXISTS (
    SELECT 1 FROM risk_assessment ra WHERE ra.demand_id = d.id
);

-- Check 4: Verify demand statuses are consistent
SELECT 
    '4. Approved Demands with Risk Assessments' as check_name,
    COUNT(*) as count,
    'INFO' as status
FROM demand d
JOIN risk_assessment ra ON d.id = ra.demand_id
WHERE d.status = 'APPROVED';

SELECT 
    '4b. In-Progress Demands with Risk Assessments' as check_name,
    COUNT(*) as count,
    'INFO' as status
FROM demand d
JOIN risk_assessment ra ON d.id = ra.demand_id
WHERE d.status = 'IN_PROGRESS';

-- Check 5: Verify asset relationships
SELECT 
    '5. Assets Referenced by Both Demands and Risk Assessments' as check_name,
    COUNT(DISTINCT a.id) as count,
    'INFO' as status
FROM asset a
JOIN demand d ON a.id = d.existing_asset_id
JOIN risk_assessment ra ON d.id = ra.demand_id;

-- Summary Report
SELECT 
    'MIGRATION SUMMARY' as report_type,
    (SELECT COUNT(*) FROM demand) as total_demands,
    (SELECT COUNT(*) FROM demand WHERE title LIKE 'Migration Demand for Asset:%') as migration_demands,
    (SELECT COUNT(*) FROM demand WHERE title NOT LIKE 'Migration Demand for Asset:%') as regular_demands,
    (SELECT COUNT(*) FROM risk_assessment) as total_risk_assessments,
    (SELECT COUNT(*) FROM risk_assessment WHERE demand_id IS NOT NULL) as demand_based_assessments,
    (SELECT COUNT(*) FROM risk_assessment WHERE demand_id IS NULL AND asset_id IS NOT NULL) as legacy_assessments,
    NOW() as report_date;

-- Detailed breakdown by demand type
SELECT 
    'DEMAND TYPE BREAKDOWN' as report_type,
    demand_type,
    status,
    COUNT(*) as count
FROM demand 
GROUP BY demand_type, status
ORDER BY demand_type, status;