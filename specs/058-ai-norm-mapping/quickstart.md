# Quickstart: AI-Powered Norm Mapping

## Prerequisites

1. **OpenRouter API Key**: Ensure `TranslationConfig` has an active configuration with valid API key
2. **User Role**: Must have ADMIN, REQ, or SECCHAMPION role
3. **Requirements**: At least one requirement without norm mappings

## User Flow

### Step 1: Navigate to Requirements
```
Browser → https://secman.covestro.net/requirements
```

### Step 2: Check Missing Mappings Count
Look for the indicator next to "Showing X of Y requirements":
```
Showing 168 of 168 requirements | 65 missing norm mappings
```

### Step 3: Click "Missing mapping" Button
- Button is enabled only if you have the required role
- Shows "Analyzing..." with spinner during AI processing

### Step 4: Review AI Suggestions
Modal appears with suggestions grouped by requirement:
- Each requirement shows its shortreq text
- Suggestions show: Standard, Control, Control Name, Confidence (1-5)
- Checkboxes pre-selected for confidence >= 4

### Step 5: Select and Apply
1. Review suggestions, adjust selections
2. Click "Apply Selected Mappings"
3. Success message shows count of applied mappings

## API Usage (for developers)

### Get Suggestions
```bash
curl -X POST https://secman.covestro.net/api/norm-mapping/suggest \
  -H "Authorization: Bearer $JWT_TOKEN" \
  -H "Content-Type: application/json"
```

### Apply Mappings
```bash
curl -X POST https://secman.covestro.net/api/norm-mapping/apply \
  -H "Authorization: Bearer $JWT_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "mappings": {
      "42": [
        {"normId": 15},
        {"standard": "IEC 62443-3-3", "control": "SR 1.1", "version": "2013"}
      ]
    }
  }'
```

## Configuration

### OpenRouter Setup
1. Go to Admin → Translation Config
2. Ensure active config has:
   - Valid API key from OpenRouter
   - Base URL: `https://openrouter.ai/api/v1`
3. Note: AI mapping uses Opus 4.5 regardless of translation config model setting

## Troubleshooting

| Issue | Solution |
|-------|----------|
| "AI configuration required" error | Set up TranslationConfig with valid OpenRouter API key |
| Button disabled | Check your user role (requires ADMIN, REQ, or SECCHAMPION) |
| No suggestions returned | Requirements may already have mappings, or AI found no relevant standards |
| Timeout | Large batches may take up to 60 seconds; wait for completion |

## Expected Behavior

- **Processing Time**: ~30-60 seconds for 50+ requirements
- **Confidence Levels**:
  - 5: Strong match, highly recommended
  - 4: Good match, recommended
  - 3: Possible match, review carefully
  - 1-2: Weak match, usually filtered out
- **Auto-creation**: New norms created automatically if AI suggests unknown standards
