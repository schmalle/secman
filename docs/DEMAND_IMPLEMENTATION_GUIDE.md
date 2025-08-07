# Demand-Based Risk Assessment Implementation Guide

## Overview

This document describes the implementation of the Demand entity as an intermediary layer between Risk Assessment and Asset in the SecMan security management system. The implementation introduces a structured workflow where demands must be created and approved before risk assessments can be performed.

## Architecture Changes

### Before (Direct Asset-Based)
```
Risk Assessment -----> Asset
```

### After (Demand-Based)
```
Risk Assessment -----> Demand -----> Asset (for CHANGE)
                              \----> New Asset Info (for CREATE_NEW)
```

## Core Components

### 1. Demand Entity (`/src/backendng/src/main/kotlin/com/secman/domain/Demand.kt`)

**Key Features:**
- Two demand types: `CHANGE` (modify existing asset) and `CREATE_NEW` (create new asset)
- Comprehensive status workflow: `PENDING` → `APPROVED` → `IN_PROGRESS` → `COMPLETED`
- Priority levels: `LOW`, `MEDIUM`, `HIGH`, `CRITICAL`
- Asset information handling for both existing and new assets
- Business justification and approval workflow

**Important Methods:**
- `validateAssetInformation()`: Ensures proper asset data based on demand type
- `getAssetName()`, `getAssetType()`, `getAssetOwner()`: Unified asset access regardless of type

### 2. Database Schema

**New Tables:**
- `demand`: Core demand entity
  - Demand metadata (title, description, type, priority, status)
  - Existing asset reference (for CHANGE demands)
  - New asset information (for CREATE_NEW demands)
  - Approval workflow fields
  - Audit timestamps

**Modified Tables:**
- `risk_assessment`: Added `demand_id` column (NOT NULL for new assessments)
  - Kept `asset_id` column for backward compatibility (nullable)

### 3. API Endpoints

#### Demand Management (`/api/demands`)
- `GET /api/demands` - List demands with filtering
- `GET /api/demands/{id}` - Get specific demand
- `GET /api/demands/summary` - Get demand statistics
- `GET /api/demands/approved/available` - Get approved demands available for risk assessment
- `POST /api/demands` - Create new demand
- `PUT /api/demands/{id}` - Update demand
- `POST /api/demands/{id}/approve` - Approve/reject demand
- `DELETE /api/demands/{id}` - Delete demand (if no risk assessments)

#### Updated Risk Assessment Endpoints (`/api/risk-assessments`)
- `GET /api/risk-assessments/demand/{demandId}` - Get assessments by demand
- Modified creation to require `demandId` instead of `assetId`
- Legacy `GET /api/risk-assessments/asset/{assetId}` kept for backward compatibility

### 4. Frontend Components

#### Demand Management (`/src/frontend/src/components/DemandManagement.tsx`)
- Complete CRUD interface for demands
- Conditional form fields based on demand type
- Status-based action buttons (approve/reject/edit)
- Summary dashboard with key metrics
- Filtering by status, type, and priority

#### Updated Risk Assessment Management
- Modified to work with approved demands instead of assets
- Shows demand information alongside legacy asset data
- Links to demand management for creating demands

#### New Pages
- `/demands` - Main demand management interface

## Implementation Workflow

### 1. Creating a CHANGE Demand
1. User selects existing asset
2. Provides change description and business justification
3. Sets priority level
4. Submits for approval

### 2. Creating a CREATE_NEW Demand
1. User provides new asset details (name, type, owner, description)
2. Provides business justification
3. Sets priority level
4. Submits for approval

### 3. Approval Process
1. Approver reviews demand details
2. Can approve with optional comments
3. Can reject with mandatory reason
4. Status updates to `APPROVED` or `REJECTED`

### 4. Risk Assessment Creation
1. Only approved demands can have risk assessments
2. Assessor selects from available approved demands
3. Risk assessment creation automatically updates demand status to `IN_PROGRESS`
4. Upon completion, demand status can be updated to `COMPLETED`

## Migration Strategy

### Phase 1: Schema Migration
1. Application startup creates `demand` table automatically (Hibernate)
2. Run `/scripts/migrate-to-demand-based-risk-assessments.sql`
3. Creates migration demands for existing risk assessments
4. Updates existing risk assessments to reference demands

### Phase 2: Validation
1. Run `/scripts/migration-validation.sql`
2. Verify data integrity
3. Check for orphaned records
4. Validate relationships

### Phase 3: Rollback (if needed)
1. Use `/scripts/rollback-demand-migration.sql`
2. Creates placeholder assets for CREATE_NEW demands
3. Restores asset-based risk assessments

## Testing

### Unit Tests
- `DemandServiceTest.kt`: Domain logic validation
- `DemandControllerTest.kt`: API endpoint testing
- Covers all demand types, status transitions, and validation rules

### Integration Tests
- `DemandRiskAssessmentIntegrationTest.kt`: End-to-end workflow testing
- Tests complete demand-to-risk-assessment lifecycle
- Validates migration compatibility

## Security Considerations

### Access Control
- Demand creation: Authenticated users
- Demand approval: Admin/Manager roles (implement role-based access)
- Risk assessment creation: Assessor roles

### Data Validation
- Strict validation for demand types and required fields
- Business rule enforcement (only approved demands can have risk assessments)
- Asset information completeness validation

## Performance Considerations

### Database Queries
- Indexed demand status and type fields
- Efficient queries for approved demands
- Optimized joins for risk assessment listings

### Caching
- Consider caching approved demands list
- Cache demand summaries for dashboard

## Best Practices

### For Administrators
1. Regularly review pending demands by priority
2. Ensure proper business justification before approval
3. Monitor demand-to-risk-assessment completion rates

### For Users
1. Provide detailed business justification
2. Set appropriate priority levels
3. Include complete asset information for CREATE_NEW demands

### For Developers
1. Always validate demand status before creating risk assessments
2. Use the unified asset access methods (`getAssetName()`, etc.)
3. Handle both legacy and demand-based risk assessments gracefully

## Troubleshooting

### Common Issues

1. **Risk Assessment Creation Fails**
   - Ensure demand is in `APPROVED` status
   - Check that demand hasn't already been used for risk assessment

2. **Migration Issues**
   - Verify all existing risk assessments have valid asset references
   - Check for data consistency before migration
   - Use validation script to identify issues

3. **Frontend Display Issues**
   - Handle both legacy asset and demand-based risk assessments
   - Gracefully display missing information

### Monitoring

1. **Database Queries**
   - Monitor slow queries on demand/risk assessment joins
   - Watch for N+1 query issues in listings

2. **Business Metrics**
   - Track demand approval rates
   - Monitor average time from demand to risk assessment
   - Measure completion rates by priority

## Future Enhancements

### Short Term
1. Role-based approval workflows
2. Email notifications for demand status changes
3. Bulk operations for demand management

### Long Term
1. Integration with CMDB for asset validation
2. Automated demand generation from change requests
3. Advanced analytics and reporting
4. Workflow automation based on priority levels

## API Documentation Updates

### New Request/Response Models

```typescript
// Demand Creation Request
interface CreateDemandRequest {
  title: string;
  description?: string;
  demandType: 'CHANGE' | 'CREATE_NEW';
  existingAssetId?: number; // Required for CHANGE
  newAssetName?: string;    // Required for CREATE_NEW
  newAssetType?: string;    // Required for CREATE_NEW
  newAssetOwner?: string;   // Required for CREATE_NEW
  newAssetDescription?: string;
  businessJustification?: string;
  priority?: 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL';
  requestorId: number;
}

// Risk Assessment Creation Request (Updated)
interface CreateRiskAssessmentRequest {
  demandId: number;         // Changed from assetId
  assessorId: number;
  endDate: string;
  startDate?: string;
  respondentId?: number;
  notes?: string;
  useCaseIds?: number[];
}
```

## Conclusion

The demand-based risk assessment system provides a more structured and controlled approach to asset risk management. By introducing the demand approval workflow, organizations can ensure that all risk assessments are properly justified and prioritized, leading to better resource allocation and security outcomes.

The implementation maintains backward compatibility while providing a clear migration path and comprehensive testing coverage. The modular design allows for future enhancements while preserving the existing functionality for current users.