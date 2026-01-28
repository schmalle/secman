# Data Model: MCP Lense Reports

**Feature Branch**: `069-mcp-lense-reports`
**Date**: 2026-01-27

## New DTOs

### RiskAssessmentSummaryDto

Aggregated risk assessment summary matching the Lense UI Reports page.

```kotlin
@Serdeable
data class RiskAssessmentSummaryDto(
    val assessmentSummary: AssessmentSummaryDto,
    val riskSummary: RiskSummaryDto,
    val assetCoverage: AssetCoverageDto,
    val recentAssessments: List<RecentAssessmentDto>,
    val highPriorityRisks: List<HighPriorityRiskDto>
)

@Serdeable
data class AssessmentSummaryDto(
    val total: Long,
    val statusBreakdown: Map<String, Long>  // e.g., {"STARTED": 5, "IN_PROGRESS": 3, "COMPLETED": 10}
)

@Serdeable
data class RiskSummaryDto(
    val total: Long,
    val statusBreakdown: Map<String, Long>,     // e.g., {"OPEN": 15, "MITIGATED": 8, "CLOSED": 5}
    val riskLevelBreakdown: Map<String, Long>   // {"Low": 5, "Medium": 10, "High": 3, "Critical": 2}
)

@Serdeable
data class AssetCoverageDto(
    val totalAssets: Long,
    val assetsWithAssessments: Long,
    val coveragePercentage: Double  // 0.0 - 100.0
)

@Serdeable
data class RecentAssessmentDto(
    val id: Long,
    val assetName: String,
    val status: String,
    val assessor: String,          // assessor username
    val startDate: String,         // ISO-8601 date
    val endDate: String            // ISO-8601 date
)

@Serdeable
data class HighPriorityRiskDto(
    val id: Long,
    val name: String,
    val assetName: String,
    val riskLevel: Int,            // 1-4
    val riskLevelText: String,     // "Low", "Medium", "High", "Critical"
    val status: String,
    val owner: String?,            // owner username, nullable
    val severity: String?,
    val deadline: String?          // ISO-8601 date, nullable
)
```

### RiskMitigationStatusDto

Risk mitigation tracking report data.

```kotlin
@Serdeable
data class RiskMitigationStatusDto(
    val summary: MitigationSummaryDto,
    val risks: List<RiskMitigationDetailDto>
)

@Serdeable
data class MitigationSummaryDto(
    val totalOpenRisks: Long,
    val overdueRisks: Long,
    val unassignedRisks: Long
)

@Serdeable
data class RiskMitigationDetailDto(
    val id: Long,
    val name: String,
    val description: String?,
    val assetName: String,
    val riskLevel: Int,
    val riskLevelText: String,
    val status: String,
    val owner: String?,
    val severity: String?,
    val deadline: String?,        // ISO-8601 date
    val isOverdue: Boolean,
    val likelihood: Int,
    val impact: Int
)
```

### VulnerabilityStatisticsAggregateDto

Aggregated vulnerability statistics for MCP (combines multiple service calls).

```kotlin
@Serdeable
data class VulnerabilityStatisticsAggregateDto(
    val severityDistribution: SeverityDistributionDto,
    val mostCommonVulnerabilities: List<MostCommonVulnerabilityDto>,
    val mostVulnerableProducts: List<MostVulnerableProductDto>,
    val topAssetsByVulnerabilities: List<TopAssetByVulnerabilitiesDto>,
    val vulnerabilitiesByAssetType: List<VulnerabilityByAssetTypeDto>,
    val topServersByVulnerabilities: List<TopServerByVulnerabilitiesDto>
)

@Serdeable
data class TopServerByVulnerabilitiesDto(
    val assetId: Long,
    val serverName: String,
    val serverIp: String?,
    val totalVulnerabilityCount: Long,
    val criticalCount: Long,
    val highCount: Long
)
```

### ExceptionStatisticsDto (Existing - Reference)

Already exists in codebase. Used as-is for the MCP tool.

```kotlin
@Serdeable
data class ExceptionStatisticsDto(
    val totalRequests: Long,
    val approvalRatePercent: Double?,
    val averageApprovalTimeHours: Double?,
    val requestsByStatus: Map<String, Long>,
    val topRequesters: List<TopRequesterDto>,
    val topCVEs: List<TopCVEDto>
)
```

## Existing Entities (Reference)

### Risk Entity Fields

| Field | Type | Description |
|-------|------|-------------|
| id | Long | Primary key |
| name | String | Risk name |
| description | String? | Detailed description |
| likelihood | Int | 1-5 scale |
| impact | Int | 1-5 scale |
| riskLevel | Int | 1-4, auto-computed |
| status | String | "OPEN", "MITIGATED", "CLOSED" |
| severity | String? | Optional severity |
| deadline | LocalDate? | Mitigation deadline |
| owner | User? | Assigned owner |
| asset | Asset? | Associated asset |

### RiskAssessment Entity Fields

| Field | Type | Description |
|-------|------|-------------|
| id | Long | Primary key |
| startDate | LocalDate | Assessment start |
| endDate | LocalDate | Assessment end |
| status | String | "STARTED", "IN_PROGRESS", "COMPLETED" |
| assessmentBasisType | Enum | DEMAND or ASSET |
| assessmentBasisId | Long | ID of basis entity |
| assessor | User | Who performs assessment |
| requestor | User | Who requested it |

## Repository Query Requirements

### New Queries for ReportService

```kotlin
// RiskRepository additions
fun countByStatus(): Map<String, Long>
fun countByRiskLevel(): Map<Int, Long>
fun countOpenRisks(): Long
fun countOverdueRisks(today: LocalDate): Long
fun countUnassignedRisks(): Long  // owner is null AND status = OPEN

// RiskAssessmentRepository additions
fun countByStatus(): Map<String, Long>
fun findRecentAssessments(limit: Int): List<RiskAssessment>
fun countAssetsWithAssessments(): Long
```

### Access Control Filtering

All queries must support optional asset ID filtering:
- If `accessibleAssetIds` is null → no filtering (ADMIN or non-delegated)
- If `accessibleAssetIds` is Set<Long> → filter to only those assets
- Empty set → return empty results

## Validation Rules

| Field | Validation |
|-------|------------|
| status filter | Must match valid status values for entity type |
| domain filter | Case-insensitive match against asset.adDomain |
| date range | start_date <= end_date |
| limit | 1-100 for pagination, fixed values for reports |

## State Transitions

### Risk Status Flow

```
OPEN → MITIGATED → CLOSED
  ↓        ↑
  └────────┘ (can reopen)
```

### RiskAssessment Status Flow

```
STARTED → IN_PROGRESS → COMPLETED
```
