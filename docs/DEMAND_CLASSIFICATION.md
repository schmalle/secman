# Demand Classification System Documentation

## Overview

The Demand Classification System is a comprehensive feature that automatically classifies security demands into risk categories (A, B, or C) based on configurable rules. This system helps organizations:

- Automatically assess security risk levels for new demands
- Apply consistent classification criteria across all demands
- Generate unique hashes for classification verification
- Provide both authenticated and public access to classification tools

## Architecture

### Components

1. **Backend (Kotlin/Micronaut)**
   - Rule Engine: Evaluates complex logical conditions
   - Classification Service: Processes demands and applies rules
   - REST API Controllers: Provides endpoints for classification and rule management
   - Database Entities: Stores rules and classification results

2. **Frontend (React/Astro)**
   - Classification Rule Manager: Admin interface for rule configuration
   - Public Classification Tool: Standalone page for public access
   - Demand Management Integration: Optional classification during demand creation

3. **Database Schema**
   - `demand_classification_rule`: Stores classification rules
   - `demand_classification_result`: Stores classification results and history
   - `demand`: Extended with classification fields

## Classification Categories

- **A (High Risk)**: Critical security requirements needed
- **B (Medium Risk)**: Standard security controls required  
- **C (Low Risk)**: Basic security measures sufficient

## Rule Engine

### Supported Condition Types

- **IF**: Evaluates a single condition
- **AND**: All sub-conditions must be true
- **OR**: At least one sub-condition must be true
- **NOT**: Inverts the result of a condition
- **COMPARISON**: Compares field values

### Supported Operators

- `EQUALS`, `NOT_EQUALS`
- `CONTAINS`, `NOT_CONTAINS`
- `STARTS_WITH`, `ENDS_WITH`
- `GREATER_THAN`, `LESS_THAN`
- `GREATER_THAN_OR_EQUAL`, `LESS_THAN_OR_EQUAL`
- `IN`, `NOT_IN`
- `IS_NULL`, `IS_NOT_NULL`

### Available Fields

- `title`: Demand title
- `description`: Demand description
- `demandType`: CHANGE or CREATE_NEW
- `priority`: LOW, MEDIUM, HIGH, CRITICAL
- `businessJustification`: Business justification text
- `assetType`: Type of asset
- `assetOwner`: Owner of asset
- `custom.*`: Custom fields (extensible)

## API Endpoints

### Public Endpoints (No Authentication)

#### POST /api/classification/public/classify
Classify a demand without authentication.

**Request Body:**
```json
{
  "title": "New Database Server",
  "description": "Deploy a new database server",
  "demandType": "CREATE_NEW",
  "priority": "HIGH",
  "businessJustification": "Required for new application",
  "assetType": "Database",
  "assetOwner": "IT Department"
}
```

**Response:**
```json
{
  "classification": "A",
  "classificationHash": "a1b2c3d4e5f6...",
  "confidenceScore": 0.95,
  "appliedRuleName": "High Priority New Asset Rule",
  "evaluationLog": ["Starting classification...", "Rule matched..."],
  "timestamp": "2025-08-09T10:30:00"
}
```

#### GET /api/classification/results/{hash}
Retrieve a classification result by its hash.

### Authenticated Endpoints

#### GET /api/classification/rules
List all classification rules.

#### POST /api/classification/rules
Create a new classification rule (Admin only).

**Request Body:**
```json
{
  "name": "Critical Database Rule",
  "description": "Classify critical database changes as A",
  "condition": {
    "type": "AND",
    "conditions": [
      {
        "type": "COMPARISON",
        "field": "assetType",
        "operator": "CONTAINS",
        "value": "Database"
      },
      {
        "type": "COMPARISON",
        "field": "priority",
        "operator": "IN",
        "value": ["HIGH", "CRITICAL"]
      }
    ]
  },
  "classification": "A",
  "confidenceScore": 0.9
}
```

#### PUT /api/classification/rules/{id}
Update an existing rule (Admin only).

#### DELETE /api/classification/rules/{id}
Delete or deactivate a rule (Admin only).

#### POST /api/classification/rules/import
Import rules from a JSON file (Admin only).

#### GET /api/classification/rules/export
Export all rules as JSON.

#### POST /api/classification/test
Test classification with specific input.

#### POST /api/classification/classify-demand
Classify an existing demand by ID.

#### GET /api/classification/statistics
Get classification statistics.

## UI Components

### Classification Rule Manager (/classification-rules)

Admin interface for managing classification rules:
- Create, edit, delete rules
- Visual rule builder with condition editor
- Rule priority management
- Import/export functionality
- Test classification with sample data

### Public Classification Tool (/public-classification)

Standalone public page for demand classification:
- No authentication required
- User-friendly form for demand details
- Instant classification results
- Classification hash for verification
- Detailed evaluation log (optional)

### Demand Management Integration

Optional classification during demand creation:
- Checkbox to enable classification
- Preview classification before saving
- Automatic classification on submit
- Classification result display

## Rule Configuration Examples

### Example 1: Complex Security Rule
```json
{
  "name": "High Security Requirement",
  "condition": {
    "type": "OR",
    "conditions": [
      {
        "type": "AND",
        "conditions": [
          {
            "type": "COMPARISON",
            "field": "assetType",
            "operator": "EQUALS",
            "value": "Payment System"
          },
          {
            "type": "COMPARISON",
            "field": "demandType",
            "operator": "EQUALS",
            "value": "CHANGE"
          }
        ]
      },
      {
        "type": "COMPARISON",
        "field": "priority",
        "operator": "EQUALS",
        "value": "CRITICAL"
      }
    ]
  },
  "classification": "A",
  "confidenceScore": 0.95
}
```

### Example 2: Department-based Rule
```json
{
  "name": "Finance Department Rule",
  "condition": {
    "type": "AND",
    "conditions": [
      {
        "type": "COMPARISON",
        "field": "assetOwner",
        "operator": "CONTAINS",
        "value": "Finance"
      },
      {
        "type": "NOT",
        "conditions": [
          {
            "type": "COMPARISON",
            "field": "priority",
            "operator": "EQUALS",
            "value": "LOW"
          }
        ]
      }
    ]
  },
  "classification": "B",
  "confidenceScore": 0.85
}
```

## Database Migration

### Apply Migration
```sql
-- Run the migration script
mysql -u secman -pCHANGEME secman < sql/migrations/V1__demand_classification.sql
```

### Rollback Migration
```sql
-- Run the rollback script (WARNING: This will delete all classification data)
mysql -u secman -pCHANGEME secman < sql/migrations/rollback_V1__demand_classification.sql
```

## Security Considerations

1. **Public Access**: The public classification endpoint is intentionally unauthenticated to allow external users to classify demands
2. **Admin Controls**: Rule management requires ROLE_ADMIN privileges
3. **Hash Verification**: Each classification generates a unique SHA-256 hash for verification
4. **Audit Trail**: All classifications are logged with timestamps and applied rules
5. **Input Validation**: All inputs are validated and sanitized before processing

## Configuration

### Backend Configuration (application.yml)
```yaml
classification:
  public-access: true
  max-rule-depth: 10
  default-confidence: 0.5
  enable-logging: true
```

### Environment Variables
```bash
# Optional: Configure classification behavior
CLASSIFICATION_PUBLIC_ACCESS=true
CLASSIFICATION_MAX_RULES=100
CLASSIFICATION_CACHE_TTL=3600
```

## Testing

### Manual Testing
1. Access public classification at: http://localhost:4321/public-classification
2. Login as admin and access rule manager at: http://localhost:4321/classification-rules
3. Create a demand with classification enabled
4. Test various rule combinations

### API Testing
```bash
# Test public classification
curl -X POST http://localhost:8080/api/classification/public/classify \
  -H "Content-Type: application/json" \
  -d '{
    "title": "Test Demand",
    "demandType": "CHANGE",
    "priority": "HIGH"
  }'

# Get classification by hash
curl http://localhost:8080/api/classification/results/{hash}
```

## Troubleshooting

### Common Issues

1. **Rules not applying**
   - Check rule priority order
   - Verify rule is active
   - Review evaluation log for details

2. **Classification returns default (C)**
   - No matching rules found
   - Check rule conditions match input data
   - Verify field names and values

3. **Import fails**
   - Validate JSON format
   - Check for duplicate rule names
   - Ensure all required fields present

### Debug Mode
Enable debug logging in application.yml:
```yaml
logger:
  levels:
    com.secman.service.DemandClassificationService: DEBUG
```

## Performance Considerations

- Rules are evaluated in priority order (lowest number first)
- Early termination on first match improves performance
- Consider rule complexity when setting priorities
- Database indexes on classification fields optimize queries

## Future Enhancements

- Machine learning integration for rule suggestions
- Historical analysis and reporting
- Rule versioning and change tracking
- Batch classification for multiple demands
- Custom field extensibility
- Integration with external risk assessment systems