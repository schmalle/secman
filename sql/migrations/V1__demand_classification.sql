-- Migration script for Demand Classification Feature
-- Version: 1.0
-- Date: 2025-08-09
-- Description: Add tables and columns for demand classification system

-- Create demand_classification_rule table
CREATE TABLE IF NOT EXISTS demand_classification_rule (
    id BIGINT NOT NULL AUTO_INCREMENT,
    name VARCHAR(255) NOT NULL UNIQUE,
    description VARCHAR(1024),
    rule_json TEXT NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    priority_order INT NOT NULL DEFAULT 0,
    created_by BIGINT,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    FOREIGN KEY (created_by) REFERENCES user(id) ON DELETE SET NULL,
    INDEX idx_active_priority (active, priority_order),
    INDEX idx_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Create demand_classification_result table
CREATE TABLE IF NOT EXISTS demand_classification_result (
    id BIGINT NOT NULL AUTO_INCREMENT,
    demand_id BIGINT,
    classification ENUM('A', 'B', 'C') NOT NULL,
    confidence_score DOUBLE,
    applied_rule_id BIGINT,
    rule_evaluation_log TEXT,
    classification_hash VARCHAR(64) NOT NULL UNIQUE,
    input_data TEXT,
    classified_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    is_manual_override BOOLEAN DEFAULT FALSE,
    overridden_by BIGINT,
    PRIMARY KEY (id),
    FOREIGN KEY (demand_id) REFERENCES demand(id) ON DELETE CASCADE,
    FOREIGN KEY (applied_rule_id) REFERENCES demand_classification_rule(id) ON DELETE SET NULL,
    FOREIGN KEY (overridden_by) REFERENCES user(id) ON DELETE SET NULL,
    INDEX idx_demand_id (demand_id),
    INDEX idx_classification (classification),
    INDEX idx_hash (classification_hash),
    INDEX idx_classified_at (classified_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Add classification columns to demand table if they don't exist
ALTER TABLE demand 
ADD COLUMN IF NOT EXISTS classification ENUM('A', 'B', 'C'),
ADD COLUMN IF NOT EXISTS classification_hash VARCHAR(64),
ADD COLUMN IF NOT EXISTS classification_confidence DOUBLE,
ADD INDEX IF NOT EXISTS idx_demand_classification (classification);

-- Insert default classification rules
INSERT INTO demand_classification_rule (name, description, rule_json, active, priority_order)
VALUES 
(
    'Critical Priority Rule',
    'Classify demands with CRITICAL priority as A',
    '{
        "name": "Critical Priority Rule",
        "description": "Classify demands with CRITICAL priority as A",
        "condition": {
            "type": "COMPARISON",
            "field": "priority",
            "operator": "EQUALS",
            "value": "CRITICAL"
        },
        "classification": "A",
        "confidenceScore": 0.95
    }',
    TRUE,
    1
),
(
    'High Priority New Asset Rule',
    'Classify new asset creation with HIGH priority as A',
    '{
        "name": "High Priority New Asset Rule",
        "description": "Classify new asset creation with HIGH priority as A",
        "condition": {
            "type": "AND",
            "conditions": [
                {
                    "type": "COMPARISON",
                    "field": "demandType",
                    "operator": "EQUALS",
                    "value": "CREATE_NEW"
                },
                {
                    "type": "COMPARISON",
                    "field": "priority",
                    "operator": "EQUALS",
                    "value": "HIGH"
                }
            ]
        },
        "classification": "A",
        "confidenceScore": 0.9
    }',
    TRUE,
    2
),
(
    'Database Asset Rule',
    'Classify database-related assets as B',
    '{
        "name": "Database Asset Rule",
        "description": "Classify database-related assets as B",
        "condition": {
            "type": "OR",
            "conditions": [
                {
                    "type": "COMPARISON",
                    "field": "assetType",
                    "operator": "CONTAINS",
                    "value": "database"
                },
                {
                    "type": "COMPARISON",
                    "field": "assetType",
                    "operator": "CONTAINS",
                    "value": "Database"
                },
                {
                    "type": "COMPARISON",
                    "field": "assetType",
                    "operator": "CONTAINS",
                    "value": "DB"
                }
            ]
        },
        "classification": "B",
        "confidenceScore": 0.85
    }',
    TRUE,
    3
),
(
    'Medium Priority Change Rule',
    'Classify changes with MEDIUM priority as B',
    '{
        "name": "Medium Priority Change Rule",
        "description": "Classify changes with MEDIUM priority as B",
        "condition": {
            "type": "AND",
            "conditions": [
                {
                    "type": "COMPARISON",
                    "field": "demandType",
                    "operator": "EQUALS",
                    "value": "CHANGE"
                },
                {
                    "type": "COMPARISON",
                    "field": "priority",
                    "operator": "EQUALS",
                    "value": "MEDIUM"
                }
            ]
        },
        "classification": "B",
        "confidenceScore": 0.8
    }',
    TRUE,
    4
),
(
    'Low Priority Rule',
    'Classify LOW priority demands as C',
    '{
        "name": "Low Priority Rule",
        "description": "Classify LOW priority demands as C",
        "condition": {
            "type": "COMPARISON",
            "field": "priority",
            "operator": "EQUALS",
            "value": "LOW"
        },
        "classification": "C",
        "confidenceScore": 0.9
    }',
    TRUE,
    5
),
(
    'Default Rule',
    'Default classification for unmatched demands',
    '{
        "name": "Default Rule",
        "description": "Default classification for unmatched demands",
        "condition": {
            "type": "COMPARISON",
            "field": "title",
            "operator": "IS_NOT_NULL"
        },
        "classification": "C",
        "confidenceScore": 0.5
    }',
    TRUE,
    100
);

-- Create stored procedure for classification statistics
DELIMITER $$

CREATE PROCEDURE IF NOT EXISTS GetClassificationStatistics()
BEGIN
    SELECT 
        'total_classifications' as metric,
        COUNT(*) as value
    FROM demand_classification_result
    
    UNION ALL
    
    SELECT 
        CONCAT('classification_', classification) as metric,
        COUNT(*) as value
    FROM demand_classification_result
    GROUP BY classification
    
    UNION ALL
    
    SELECT 
        'active_rules' as metric,
        COUNT(*) as value
    FROM demand_classification_rule
    WHERE active = TRUE
    
    UNION ALL
    
    SELECT 
        'demands_with_classification' as metric,
        COUNT(*) as value
    FROM demand
    WHERE classification IS NOT NULL;
END$$

DELIMITER ;

-- Grant necessary permissions (adjust user as needed)
-- GRANT SELECT, INSERT, UPDATE, DELETE ON demand_classification_rule TO 'secman'@'%';
-- GRANT SELECT, INSERT, UPDATE, DELETE ON demand_classification_result TO 'secman'@'%';
-- GRANT EXECUTE ON PROCEDURE GetClassificationStatistics TO 'secman'@'%';