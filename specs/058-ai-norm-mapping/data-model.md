# Data Model: AI-Powered Norm Mapping

**Feature**: 058-ai-norm-mapping
**Date**: 2026-01-02

## Existing Entities (No Changes Required)

### Requirement

**Table**: `requirement`

| Field | Type | Constraints | Notes |
|-------|------|-------------|-------|
| id | Long | PK, auto-generated | |
| shortreq | String | NOT NULL | Text analyzed by AI |
| details | Text | nullable | |
| language | String | nullable | |
| norms | Set<Norm> | ManyToMany | **Used for storing mappings** |
| ... | ... | ... | Other fields unchanged |

**Relationship**: `requirement_norm` junction table (existing)

### Norm

**Table**: `norm`

| Field | Type | Constraints | Notes |
|-------|------|-------------|-------|
| id | Long | PK, auto-generated | |
| name | String | UNIQUE, NOT NULL | e.g., "ISO 27001: A.8.1.1" |
| version | String | default "" | e.g., "2022" |
| year | Int | nullable | Optional year |
| createdAt | Date | auto | |
| updatedAt | Date | auto | |

### TranslationConfig

**Table**: `translation_config` (existing, read-only for this feature)

| Field | Type | Usage |
|-------|------|-------|
| apiKey | String | OpenRouter API authentication |
| baseUrl | String | Default: `https://openrouter.ai/api/v1` |
| isActive | Boolean | Must be true to use |

## New DTOs (Transient - No Database Storage)

### NormMappingSuggestionRequest

```kotlin
@Serdeable
data class NormMappingSuggestionRequest(
    val requirementIds: List<Long>? = null  // Optional filter, null = all unmapped
)
```

### NormMappingSuggestionResponse

```kotlin
@Serdeable
data class NormMappingSuggestionResponse(
    val suggestions: List<RequirementSuggestions>,
    val totalRequirementsAnalyzed: Int,
    val totalSuggestionsGenerated: Int
)

@Serdeable
data class RequirementSuggestions(
    val requirementId: Long,
    val requirementTitle: String,
    val suggestions: List<NormSuggestion>
)

@Serdeable
data class NormSuggestion(
    val standard: String,           // e.g., "ISO 27001:2022"
    val control: String,            // e.g., "A.8.1.1"
    val controlName: String,        // e.g., "Inventory of assets"
    val confidence: Int,            // 1-5
    val reasoning: String,          // Brief explanation
    val normId: Long? = null        // ID if norm exists in DB, null if will be created
)
```

### ApplyMappingsRequest

```kotlin
@Serdeable
data class ApplyMappingsRequest(
    val mappings: Map<Long, List<NormToApply>>  // requirementId -> norms to add
)

@Serdeable
data class NormToApply(
    val normId: Long? = null,       // Existing norm ID
    val standard: String? = null,   // For new norm creation
    val control: String? = null,    // For new norm creation
    val version: String? = null     // For new norm creation
)
```

### ApplyMappingsResponse

```kotlin
@Serdeable
data class ApplyMappingsResponse(
    val updatedRequirements: Int,
    val newNormsCreated: Int,
    val existingNormsLinked: Int
)
```

## Entity Relationships

```
┌─────────────────┐       ┌────────────────────┐       ┌──────────────┐
│   Requirement   │──────<│  requirement_norm  │>──────│     Norm     │
│                 │       │   (junction)       │       │              │
│ id              │       │ requirement_id     │       │ id           │
│ shortreq        │       │ norm_id            │       │ name         │
│ norms: Set<Norm>│       └────────────────────┘       │ version      │
└─────────────────┘                                     │ year         │
                                                        └──────────────┘
```

## Data Flow

1. **Suggest Mappings**:
   - Query: `SELECT * FROM requirement WHERE id NOT IN (SELECT requirement_id FROM requirement_norm)`
   - Send shortreq texts to OpenRouter API
   - Parse JSON response into `RequirementSuggestions` DTOs
   - Return suggestions to frontend

2. **Apply Mappings**:
   - For each selected mapping:
     - If `normId` provided: Fetch existing Norm
     - If `normId` null: Create new Norm from `standard + control`
   - Add Norm to Requirement.norms set
   - Save Requirement (cascades junction table insert)

## Validation Rules

| Entity | Rule | Enforcement |
|--------|------|-------------|
| Norm | name must be unique | DB constraint + existsByName check |
| Norm | name cannot be blank | @NotBlank annotation |
| Requirement | must have valid ID | Fetch or 404 |
| NormSuggestion | confidence 1-5 | Validated in DTO |

## State Transitions

```
Requirement State:
  [No Norms] ──(AI Suggest)──> [Suggestions Pending] ──(Apply)──> [Has Norms]
                                        │
                                        └──(Cancel)──> [No Norms]

Norm State:
  [Does Not Exist] ──(Apply with new norm)──> [Created]
  [Exists] ──(Apply with existing)──> [Linked to Requirement]
```
