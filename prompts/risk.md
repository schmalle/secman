
# Risk Model Requirements (Play Framework)

## Entity: `Risk`

- `id`: Long, primary key, auto-generated
- `name`: String, required, max 255 characters, must not be blank
- `description`: String, required, max 1024 characters
- `likelihood`: Integer, required, allowed values 1–5 (1=Very Low, 5=Very High)
- `impact`: Integer, required, allowed values 1–5 (1=Very Low, 5=Very High)
- `riskLevel`: Integer, required, computed as follows:
    - Calculate `likelihood * impact` and map:
        - 1–4   → 1 (Low Risk)
        - 5–9   → 2 (Medium Risk)
        - 10–15 → 3 (High Risk)
        - 16–25 → 4 (Very High Risk)
- `createdAt`: LocalDateTime, set automatically on creation, not updatable
- `updatedAt`: LocalDateTime, set automatically on update

### Additional Requirements

- Use JPA/Hibernate annotations for all fields.
- Use Bean Validation annotations (`@NotBlank`, `@Min`, `@Max`) for input validation.
- Implement `@PrePersist` and `@PreUpdate` lifecycle methods to set timestamps and compute `riskLevel`.
- Provide standard getters and setters for all fields.

## Evolution Script Requirement

You must provide a Play evolution script (e.g., `conf/evolutions/default/4.sql`) that creates the `risk` table with all specified fields and constraints. The script must include both `!Ups` (table creation) and `!Downs` (table removal) sections, following the conventions used in the project for other entities such as `norm`.

Example:

```sql
## UI Requirement

Develop a user interface for managing risks that is visually consistent with the existing Norm Management UI in this project. The UI should:

- Allow users to create, view, edit, and delete risks.
- Display all relevant fields, including computed `riskLevel` and timestamps.
- Use the same layout, styling, and interaction patterns as the Norm Management section (see `NormManagement.tsx` and related Astro pages).
- Provide clear validation feedback and a user-friendly experience.
-- !Ups
CREATE TABLE risk (
  id           BIGINT AUTO_INCREMENT PRIMARY KEY,
  name         VARCHAR(255)    NOT NULL,
  description  VARCHAR(1024)   NOT NULL,
  likelihood   INT             NOT NULL,
  impact       INT             NOT NULL,
  risk_level   INT             NOT NULL,
  created_at   DATETIME        NOT NULL,
  updated_at   DATETIME        NOT NULL
);
-- !Downs
DROP TABLE IF EXISTS risk;
```




