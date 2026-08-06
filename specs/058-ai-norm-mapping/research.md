# Research: AI-Powered Norm Mapping

**Feature**: 058-ai-norm-mapping
**Date**: 2026-01-02

## Research Tasks Completed

### 1. OpenRouter API Integration Pattern

**Decision**: Reuse existing `TranslationService` pattern for OpenRouter HTTP calls

**Rationale**:
- TranslationService already implements correct OpenRouter `/chat/completions` endpoint usage
- Uses proper authentication (Bearer token), headers (HTTP-Referer, X-Title)
- Handles response parsing, error extraction, and logging
- Configuration stored in `TranslationConfig` entity with encrypted API key

**Alternatives Considered**:
- Create new HTTP client: Rejected - duplicates existing proven code
- Use OpenAI SDK: Rejected - OpenRouter uses same API format, direct HTTP sufficient

**Key Pattern from TranslationService.kt**:
```kotlin
val httpRequest = HttpRequest.POST("${config.baseUrl}/chat/completions", request)
    .contentType(MediaType.APPLICATION_JSON)
    .header("Authorization", "Bearer ${config.apiKey}")
    .header("HTTP-Referer", "https://secman.local")
    .header("X-Title", "SecMan Norm Mapping Service")
```

### 2. Opus 4.5 Model Identifier

**Decision**: Use model identifier `anthropic/claude-opus-4-5-20251101`

**Rationale**:
- User explicitly requested Opus 4.5 for best mapping quality
- OpenRouter model naming follows pattern: `provider/model-version-date`
- Verified Opus 4.5 exists with date suffix `20251101`

**Alternative Considered**:
- Use TranslationConfig's configured model: Rejected - user specifically requested Opus 4.5 regardless of translation config

### 3. AI Prompt Structure for Norm Mapping

**Decision**: Use structured JSON output prompt with explicit ISO/IEC standard references

**Rationale**:
- Structured JSON output enables reliable parsing
- Including standard names helps AI focus on relevant controls
- Confidence score (1-5) allows user to prioritize suggestions

**Prompt Template**:
```
You are a security standards expert. For each security requirement below, suggest the most relevant ISO 27001 and IEC 62443 control mappings.

Requirements to analyze:
{numbered list of requirement shortreq texts}

Respond in JSON format:
{
  "mappings": [
    {
      "requirementIndex": 1,
      "suggestions": [
        {
          "standard": "ISO 27001:2022",
          "control": "A.8.1.1",
          "controlName": "Inventory of assets",
          "confidence": 4,
          "reasoning": "Brief explanation"
        }
      ]
    }
  ]
}

Only suggest mappings with confidence >= 3. Include up to 3 suggestions per requirement.
```

### 4. Requirement-Norm Relationship

**Decision**: Use existing `@ManyToMany` relationship in Requirement entity

**Rationale**:
- `Requirement.norms` already exists as `MutableSet<Norm>`
- Junction table `requirement_norm` already exists
- Simply add Norm to set and save Requirement

**Key Code Pattern**:
```kotlin
requirement.norms.add(norm)
requirementRepository.save(requirement)
```

### 5. Norm Auto-Creation Strategy

**Decision**: Create new Norm entries with parsed name/version from AI response

**Rationale**:
- Clarification confirmed auto-creation of missing norms
- NormRepository has `existsByName()` for checking existence
- Parse AI response like "ISO 27001:2022 A.8.1.1" into:
  - name: "ISO 27001: A.8.1.1"
  - version: "2022"

**Parsing Logic**:
```kotlin
fun parseNormFromAISuggestion(standard: String, control: String): Norm {
    val name = "$standard: $control"
    val versionMatch = Regex("(\\d{4})").find(standard)
    val version = versionMatch?.value ?: ""
    return Norm(name = name, version = version)
}
```

### 6. Batch Processing Approach

**Decision**: Single API call with all unmapped requirements

**Rationale**:
- Clarification confirmed batch processing for efficiency
- Reduces API costs (one call vs many)
- Opus 4.5 has large context window (200k tokens) - can handle 50+ requirements
- 65 unmapped requirements × ~50 chars avg = ~3,250 chars (trivial)

**Implementation**:
```kotlin
val unmappedRequirements = requirementRepository.findAll()
    .filter { it.norms.isEmpty() }
val prompt = buildBatchPrompt(unmappedRequirements)
val response = callOpenRouter(prompt)
```

### 7. Frontend Modal Pattern

**Decision**: Reuse existing modal pattern from `RequirementManagement.tsx`

**Rationale**:
- Component already has modal infrastructure (delete confirmation modals)
- Bootstrap modal styling consistent with app design
- Checkbox selection pattern used elsewhere in app

**Key UI Components**:
- Modal with suggestion cards grouped by requirement
- Checkbox per suggestion (pre-selected if confidence >= 4)
- "Apply Selected" and "Close" buttons
- Confidence badge (color-coded: green 4-5, yellow 3, gray 1-2)

### 8. Role-Based Access Control

**Decision**: Use `@Secured("ADMIN", "REQ", "SECCHAMPION")` annotation

**Rationale**:
- Matches clarification decision
- Same roles that can edit requirements per NormController pattern
- Frontend already checks roles for button visibility

**Backend**:
```kotlin
@Post("/suggest")
@Secured("ADMIN", "REQ", "SECCHAMPION")
fun suggestMappings(): HttpResponse<*>
```

**Frontend**:
```typescript
const canUseMappingFeature = userRoles.includes('ADMIN') ||
    userRoles.includes('REQ') || userRoles.includes('SECCHAMPION');
```

## Unknowns Resolved

| Unknown | Resolution |
|---------|------------|
| Model identifier for Opus 4.5 | `anthropic/claude-opus-4-5-20251101` |
| API configuration source | Reuse TranslationConfig entity |
| Norm parsing format | `{standard}: {control}` as name, extract year as version |
| Batch size limit | No limit needed - send all unmapped in single call |
| Role permissions | ADMIN, REQ, SECCHAMPION |

## Dependencies Confirmed

1. **TranslationConfig** - Existing entity with OpenRouter API key
2. **TranslationService** - Pattern reference for HTTP calls (not reused directly)
3. **Requirement** - Has `norms: MutableSet<Norm>` relationship
4. **Norm** - Existing entity with name, version, year fields
5. **RequirementRepository** - Standard JPA repository
6. **NormRepository** - Has `findByName()` and `existsByName()`
