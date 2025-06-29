When importing norms from the requirement Excel, the "Norm" column is currently treated as a single string.
This document outlines the desired changes to enhance this functionality and related UI aspects.

**I. Excel Import Enhancements for Norms:**

1.  **Multiple Norms per Cell:**
    *   The "Norm" cell in the Excel sheet can contain multiple norm identifiers, separated by a semicolon (`;`).
    *   Example: `ISO 27001; NIST CSF; SOC 2`

2.  **Processing During Import:**
    *   When importing, the string from the "Norm" cell must be split by the semicolon.
    *   Each part obtained after splitting should be trimmed of leading/trailing whitespace.
    *   Empty strings resulting from splitting (e.g., due to `normA;;normB` or trailing semicolons) after trimming should be ignored.

3.  **Norm Creation and Association:**
    *   For each valid, trimmed norm identifier string:
        *   The system must check if a `Norm` entity with that identifier already exists in the database. This check should be **case-insensitive** (e.g., "iso 27001" and "ISO 27001" are considered the same).
        *   If an existing `Norm` is found, it should be associated with the current `Requirement`.
        *   If no existing `Norm` is found, a new `Norm` entity must be created. The name of this new norm should be stored with its **original casing** as it appeared in the Excel sheet. This new `Norm` is then persisted and associated with the current `Requirement`.
    *   A single `Requirement` can thus be associated with multiple `Norm` entities.

4.  **Impact on Existing `Requirement` Fields:**
    *   **`Requirement.norm` (String field):** This field's role needs re-evaluation. If multiple norms are associated, this field could, for example, store the name of the first norm processed from the Excel cell, or it could be deprecated if `associatedNorms` becomes the primary source. For now, it will be populated with the name of the first successfully processed norm for the requirement.
    *   **`Requirement.chapter` (String field):** If the "Chapter" column in Excel is empty for a requirement, the chapter information should be derived from the name of the *first successfully processed and associated norm* for that requirement. The existing derivation logic (e.g., extracting the part before the first space) should be applied to this norm's name.

**II. UI for Norm Management and Association:**

1.  **Norm Management UI (`NormManagement.tsx`):**
    *   The existing UI page referenced in the sidebar as "Norm Management" (presumably `src/frontend/src/components/NormManagement.tsx`) should allow for full CRUD (Create, Read, Update, Delete) operations on `Norm` entities themselves.
    *   This includes listing all norms, creating new norms (e.g., with a name and description), editing existing norm details, and deleting norms (if not in use or with appropriate warnings/handling for existing associations).

2.  **Associating Norms with Requirements (`RequirementManagement.tsx`):**
    *   The primary interface for associating norms with a *specific* requirement should be within the requirement creation/editing form (likely in `src/frontend/src/components/RequirementManagement.tsx`).
    *   This interface should provide a mechanism (e.g., a multi-select dropdown, a list of checkboxes) for users to select one or more `Norm` entities from the master list of available norms.
    *   When a requirement is displayed, its currently associated norms should be clearly visible.

**III. Backend Implementation Notes:**

This feature will require significant backend changes:
*   **Models:**
    *   The `Requirement` and `Norm` JPA models must be updated to establish a Many-to-Many relationship. `Requirement` will likely have a `Set<Norm> associatedNorms;`.
    *   The `Norm` model will need fields like `name` (String, unique, case-insensitive for lookup but stored with original case) and potentially `description` (String).
*   **Database Evolution:**
    *   A new database evolution script will be needed to:
        *   Create the `norm` table if it doesn't exist or modify it to meet new requirements (e.g., unique constraint on name).
        *   Create a join table (e.g., `requirement_norm`) for the Many-to-Many relationship.
*   **Controllers:**
    *   `ImportController.java`: Must be updated to implement the new Excel parsing logic, norm lookup/creation, and association.
    *   `NormController.java`: Must provide robust CRUD API endpoints for `Norm` entities.
    *   `RequirementController.java`: API endpoints for creating/updating requirements must be modified to accept and process a list of associated norm identifiers (e.g., `List<Long> normIds`) and to correctly serialize the `associatedNorms` when returning requirement data.


