# Extended Risk Assessment Model Implementation

## Overview
This document describes the implementation of the extended risk assessment model that supports both demands and assets as equal basis options for risk assessments.

## Key Changes

### 1. New AssessmentBasisType Enum
- **File**: `src/backendng/src/main/kotlin/com/secman/domain/AssessmentBasisType.kt`
- **Purpose**: Defines DEMAND and ASSET as valid basis types for risk assessments
- **Values**: 
  - `DEMAND`: Risk assessment based on a demand (new or change request)
  - `ASSET`: Risk assessment based directly on an existing asset

### 2. Updated RiskAssessment Entity
- **File**: `src/backendng/src/main/kotlin/com/secman/domain/RiskAssessment.kt`
- **New Fields**:
  - `assessmentBasisType: AssessmentBasisType` - Defines whether the assessment is based on a demand or asset
  - `assessmentBasisId: Long` - The ID of the demand or asset being assessed
- **Legacy Fields**: Maintained `demand` and `asset` fields for backward compatibility (marked as deprecated)
- **Database Indexes**: Added proper indexing for efficient querying
- **Helper Methods**:
  - `getDemandBasis()` - Returns the demand if basis type is DEMAND
  - `getAssetBasis()` - Returns the asset if basis type is ASSET
  - `getAssociatedAsset()` - Unified method to get the associated asset
  - `getBasisDescription()` - Gets a descriptive name for what's being assessed
  - `validateBasisConsistency()` - Validates the configuration is consistent
  - `getBasisValidationErrors()` - Returns detailed validation errors

### 3. Updated RiskAssessmentRepository
- **File**: `src/backendng/src/main/kotlin/com/secman/repository/RiskAssessmentRepository.kt`
- **New Methods**:
  - `findByAssessmentBasisTypeAndAssessmentBasisId()` - Core query method for the unified approach
  - `findByAssessmentBasisType()` - Find by basis type only
  - `findByAssessmentBasisTypeAndStatus()` - Find by basis type and status
- **Enhanced Methods**: Updated existing methods to work with the new unified approach
- **Backward Compatibility**: Maintained existing method signatures as convenience methods

### 4. Updated RiskAssessmentController
- **File**: `src/backendng/src/main/kotlin/com/secman/controller/RiskAssessmentController.kt`
- **New Request Class**: 
  - `CreateRiskAssessmentRequest` - Unified request supporting both `demandId` and `assetId`
  - Built-in validation to ensure exactly one of `demandId` or `assetId` is provided
- **New Endpoints**:
  - `GET /api/risk-assessments/basis/{basisType}/{basisId}` - Query by basis type and ID
  - Enhanced `GET /api/risk-assessments/asset/{assetId}` - Now finds both direct and demand-based assessments
- **Legacy Endpoints**: Added deprecated endpoints for backward compatibility
- **Enhanced Queries**: Updated all queries to properly load related entities based on basis type

## Database Schema Changes

### New Columns
- `assessment_basis_type` (VARCHAR, NOT NULL) - The type of basis (DEMAND/ASSET)
- `assessment_basis_id` (BIGINT, NOT NULL) - The ID of the basis entity

### New Indexes
- `idx_risk_assessment_basis` - Composite index on (assessment_basis_type, assessment_basis_id)
- `idx_risk_assessment_assessor` - Index on assessor_id
- `idx_risk_assessment_requestor` - Index on requestor_id
- `idx_risk_assessment_status` - Index on status
- `idx_risk_assessment_dates` - Composite index on (start_date, end_date)

## API Changes

### New Unified Create Request
```json
{
  "assessorId": 123,
  "endDate": "2024-12-31",
  "startDate": "2024-01-01",
  "respondentId": 456,
  "notes": "Assessment notes",
  "useCaseIds": [1, 2, 3],
  "demandId": 789,     // Either demandId OR assetId must be provided
  "assetId": null      // But not both
}
```

### New Query Endpoints
- `GET /api/risk-assessments/basis/DEMAND/123` - Get assessments for demand 123
- `GET /api/risk-assessments/basis/ASSET/456` - Get assessments for asset 456

### Enhanced Asset Query
- `GET /api/risk-assessments/asset/123` - Now returns both direct asset assessments and demand-based assessments that involve the asset

## Backward Compatibility

### Constructor Compatibility
- Original constructors are maintained and work as before
- New constructors automatically set the correct `assessmentBasisType` and `assessmentBasisId`

### API Compatibility
- All existing endpoints continue to work
- Legacy request classes are maintained (marked as deprecated)
- Response structure remains the same

### Data Migration
- A migration script is provided: `scripts/migrate-to-extended-risk-assessment.sql`
- Existing records are automatically migrated to populate the new fields
- The migration maintains referential integrity with existing data

## Usage Examples

### Creating a Demand-based Risk Assessment
```kotlin
val riskAssessment = RiskAssessment(
    startDate = LocalDate.now(),
    endDate = LocalDate.now().plusDays(30),
    demand = demand,
    assessor = assessor,
    requestor = requestor
)
// Automatically sets assessmentBasisType = DEMAND and assessmentBasisId = demand.id
```

### Creating an Asset-based Risk Assessment
```kotlin
val riskAssessment = RiskAssessment(
    startDate = LocalDate.now(),
    endDate = LocalDate.now().plusDays(30),
    asset = asset,
    assessor = assessor,
    requestor = requestor
)
// Automatically sets assessmentBasisType = ASSET and assessmentBasisId = asset.id
```

### Querying Risk Assessments
```kotlin
// Find all assessments for a specific demand
val demandAssessments = riskAssessmentRepository.findByDemandId(demandId)

// Find all assessments for a specific asset (direct)
val assetAssessments = riskAssessmentRepository.findByAssetId(assetId)

// Find all assessments involving an asset (direct or through demands)
val allAssetAssessments = riskAssessmentRepository.findAllByInvolvedAssetId(assetId)

// Unified approach
val assessments = riskAssessmentRepository.findByAssessmentBasisTypeAndAssessmentBasisId(
    AssessmentBasisType.DEMAND, 
    demandId
)
```

## Benefits of the New Design

1. **Flexibility**: Supports both demand-driven and direct asset assessments
2. **Consistency**: Unified API for both types of assessments
3. **Performance**: Proper indexing for efficient queries
4. **Maintainability**: Clear separation of concerns with validation methods
5. **Extensibility**: Easy to add new basis types in the future
6. **Backward Compatibility**: Existing code continues to work without changes

## Future Enhancements

The design is extensible to support additional basis types such as:
- `PROJECT` - Risk assessments for entire projects
- `COMPLIANCE` - Risk assessments for compliance requirements
- `INCIDENT` - Risk assessments triggered by security incidents

## Testing

Unit tests are provided in `RiskAssessmentTest.kt` to verify:
- Correct basis type and ID assignment
- Helper method functionality
- Validation logic
- Consistency checks

## Migration Guide

1. **Database Migration**: Run the migration script before deploying the new version
2. **API Changes**: Update frontend code to use the new unified request format
3. **Legacy Support**: Existing API calls will continue to work during the transition period
4. **Validation**: Use the provided validation methods to ensure data integrity

This implementation provides a solid foundation for the extended risk assessment model while maintaining full backward compatibility with the existing system.