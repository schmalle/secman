# Use Case UI Components Analysis

## Key Files Identified

### Primary Use Case Management Files:
1. **`/Users/flake/sources/misc/secman/src/frontend/src/components/UseCaseManagement.tsx`** - Main React component for use case CRUD operations
2. **`/Users/flake/sources/misc/secman/src/frontend/src/pages/usecases.astro`** - Astro page that renders the UseCaseManagement component

### ID Field References Found:
Based on the compiled JavaScript output in `usecases.astro.mjs`, the UI includes:

1. **Table Header**: Contains "ID" column
   ```javascript
   /* @__PURE__ */ jsx("th", { children: "ID" }),
   ```

2. **Table Data**: Displays the use case ID
   ```javascript
   /* @__PURE__ */ jsx("td", { children: useCase.id }),
   ```

3. **API Calls**: Use ID for operations
   ```javascript
   const url = editingUseCase ? `/api/usecases/${editingUseCase.id}` : "/api/usecases";
   const response = await fetch(`/api/usecases/${id}`, { /* DELETE operation */ });
   ```

4. **React Keys**: Use ID as unique keys
   ```javascript
   }, useCase.id)) // React key
   ```

## Supporting Components with Use Case References:
- **RequirementManagement.tsx** - Associates requirements with use cases
- **RiskAssessmentManagement.tsx** - Links risk assessments to use cases  
- **Export.tsx** - Exports requirements by use case
- **StandardManagement.tsx** - Links standards to use cases
- **ImportExport.tsx** - Import/export functionality by use case

## To Remove ID Field:
The ID field appears in the table structure of UseCaseManagement.tsx:
1. Remove "ID" table header
2. Remove ID display in table rows (useCase.id)
3. Keep ID usage for API operations and React keys (internal use only)