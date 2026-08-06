# Tasks: Products Overview

**Input**: Design documents from `/specs/054-products-overview/`
**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/

**Tests**: Not requested per constitution (Principle IV: User-Requested Testing)

**Organization**: Tasks are grouped by user story to enable independent implementation and testing of each story.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2, US3)
- Include exact file paths in descriptions

## Path Conventions

- **Backend**: `src/backendng/src/main/kotlin/com/secman/`
- **Frontend**: `src/frontend/src/`

---

## Phase 1: Setup

**Purpose**: No additional setup required - extends existing SECMAN project structure

*No tasks - project structure already exists*

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Core backend DTOs and repository methods that ALL user stories depend on

**CRITICAL**: No user story work can begin until this phase is complete

- [ ] T001 [P] Create ProductListResponse DTO in src/backendng/src/main/kotlin/com/secman/dto/ProductDto.kt
- [ ] T002 [P] Create ProductSystemDto DTO in src/backendng/src/main/kotlin/com/secman/dto/ProductDto.kt
- [ ] T003 [P] Create PaginatedProductSystemsResponse DTO in src/backendng/src/main/kotlin/com/secman/dto/ProductDto.kt
- [ ] T004 Add findDistinctProducts query method in src/backendng/src/main/kotlin/com/secman/repository/VulnerabilityRepository.kt
- [ ] T005 Add findDistinctProductsForAdmin query method in src/backendng/src/main/kotlin/com/secman/repository/VulnerabilityRepository.kt
- [ ] T006 Add findAssetsByProductWithAccessControl query method in src/backendng/src/main/kotlin/com/secman/repository/AssetRepository.kt
- [ ] T007 Add countAssetsByProductWithAccessControl query method in src/backendng/src/main/kotlin/com/secman/repository/AssetRepository.kt

**Checkpoint**: Foundation ready - DTOs and repository queries available for all user stories

---

## Phase 3: User Story 1 - View Systems Running a Specific Product (Priority: P1) MVP

**Goal**: Users can navigate to Products page, select a product from dropdown, and see paginated table of systems with Name, IP, Domain

**Independent Test**: Select a product from dropdown, verify systems table shows correct data with pagination

### Implementation for User Story 1

- [ ] T008 [US1] Create ProductService with getProducts method in src/backendng/src/main/kotlin/com/secman/service/ProductService.kt
- [ ] T009 [US1] Add getProductSystems method to ProductService in src/backendng/src/main/kotlin/com/secman/service/ProductService.kt
- [ ] T010 [US1] Create ProductController with GET /api/products endpoint in src/backendng/src/main/kotlin/com/secman/controller/ProductController.kt
- [ ] T011 [US1] Add GET /api/products/{product}/systems endpoint to ProductController in src/backendng/src/main/kotlin/com/secman/controller/ProductController.kt
- [ ] T012 [P] [US1] Create productService.ts API client in src/frontend/src/services/productService.ts
- [ ] T013 [US1] Create ProductsOverview.tsx component with product dropdown and systems table in src/frontend/src/components/ProductsOverview.tsx
- [ ] T014 [US1] Add pagination controls to ProductsOverview in src/frontend/src/components/ProductsOverview.tsx
- [ ] T015 [US1] Add empty state handling for no products and no systems in src/frontend/src/components/ProductsOverview.tsx
- [ ] T016 [P] [US1] Create products.astro page in src/frontend/src/pages/products.astro
- [ ] T017 [US1] Add Products menu item to Sidebar under Vulnerability Management in src/frontend/src/components/Sidebar.tsx

**Checkpoint**: User Story 1 complete - users can view systems by product with pagination

---

## Phase 4: User Story 2 - Search and Filter Products (Priority: P2)

**Goal**: Users can type in search field to filter product dropdown with case-insensitive partial matching

**Independent Test**: Type partial product name, verify dropdown filters to matching products only

### Implementation for User Story 2

- [ ] T018 [US2] Add findDistinctProductsFiltered query method in src/backendng/src/main/kotlin/com/secman/repository/VulnerabilityRepository.kt
- [ ] T019 [US2] Add findDistinctProductsFilteredForAdmin query method in src/backendng/src/main/kotlin/com/secman/repository/VulnerabilityRepository.kt
- [ ] T020 [US2] Update getProducts in ProductService to accept search parameter in src/backendng/src/main/kotlin/com/secman/service/ProductService.kt
- [ ] T021 [US2] Update GET /api/products endpoint to accept search query parameter in src/backendng/src/main/kotlin/com/secman/controller/ProductController.kt
- [ ] T022 [US2] Update productService.ts getProducts to accept search parameter in src/frontend/src/services/productService.ts
- [ ] T023 [US2] Add searchable input field to product dropdown in ProductsOverview in src/frontend/src/components/ProductsOverview.tsx
- [ ] T024 [US2] Implement client-side filtering with debounced API calls in src/frontend/src/components/ProductsOverview.tsx

**Checkpoint**: User Story 2 complete - users can search/filter products

---

## Phase 5: User Story 3 - Export Product Systems List (Priority: P3)

**Goal**: Users can export the systems list for a selected product to Excel (.xlsx) format

**Independent Test**: Select product, click Export button, verify Excel file downloads with correct data

### Implementation for User Story 3

- [ ] T025 [US3] Add exportProductSystems method to ProductService in src/backendng/src/main/kotlin/com/secman/service/ProductService.kt
- [ ] T026 [US3] Add GET /api/products/{product}/systems/export endpoint to ProductController in src/backendng/src/main/kotlin/com/secman/controller/ProductController.kt
- [ ] T027 [US3] Add exportProductSystems function to productService.ts in src/frontend/src/services/productService.ts
- [ ] T028 [US3] Add Export button to ProductsOverview with download handling in src/frontend/src/components/ProductsOverview.tsx

**Checkpoint**: User Story 3 complete - users can export systems to Excel

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: Final verification and documentation

- [ ] T029 Verify all endpoints return proper error responses (401, 403) in src/backendng/src/main/kotlin/com/secman/controller/ProductController.kt
- [ ] T030 Add loading states to ProductsOverview in src/frontend/src/components/ProductsOverview.tsx
- [ ] T031 Add error alert handling to ProductsOverview in src/frontend/src/components/ProductsOverview.tsx
- [ ] T032 Run ./gradlew build to verify no compilation errors
- [ ] T033 Run quickstart.md validation checklist

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No tasks - project exists
- **Foundational (Phase 2)**: Creates DTOs and repository methods - BLOCKS all user stories
- **User Story 1 (Phase 3)**: Depends on Foundational - MVP delivery
- **User Story 2 (Phase 4)**: Depends on Foundational - enhances US1
- **User Story 3 (Phase 5)**: Depends on Foundational - extends US1
- **Polish (Phase 6)**: Depends on all user stories complete

### User Story Dependencies

- **User Story 1 (P1)**: Depends only on Foundational (Phase 2) - No dependencies on other stories
- **User Story 2 (P2)**: Depends only on Foundational (Phase 2) - Enhances US1 but independently testable
- **User Story 3 (P3)**: Depends only on Foundational (Phase 2) - Extends US1 but independently testable

### Within Each User Story

- Backend service before controller
- Controller before frontend service
- Frontend service before component
- Component before page/sidebar integration

### Parallel Opportunities

**Phase 2 (Foundational):**
- T001, T002, T003 can run in parallel (different DTOs in same file, but logically separate)
- T004, T005 can run in parallel with T006, T007 (different repositories)

**Phase 3 (User Story 1):**
- T012 (frontend service) and T016 (astro page) can run in parallel with backend work
- Backend (T008-T011) must complete before frontend integration (T013-T015, T017)

**Cross-Story Parallelism:**
- Once Foundational completes, US1, US2, US3 backend work can theoretically proceed in parallel
- Recommended: Complete US1 first for MVP validation

---

## Parallel Example: Foundational Phase

```bash
# Launch all DTO tasks together:
Task: "Create ProductListResponse DTO in src/backendng/.../dto/ProductDto.kt"
Task: "Create ProductSystemDto DTO in src/backendng/.../dto/ProductDto.kt"
Task: "Create PaginatedProductSystemsResponse DTO in src/backendng/.../dto/ProductDto.kt"

# Launch repository tasks in parallel (different files):
Task: "Add findDistinctProducts query method in .../VulnerabilityRepository.kt"
Task: "Add findAssetsByProductWithAccessControl query method in .../AssetRepository.kt"
```

---

## Parallel Example: User Story 1

```bash
# Launch frontend scaffolding while backend is in progress:
Task: "Create productService.ts API client in src/frontend/src/services/productService.ts"
Task: "Create products.astro page in src/frontend/src/pages/products.astro"

# After backend complete, launch component work:
Task: "Create ProductsOverview.tsx component in src/frontend/src/components/ProductsOverview.tsx"
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 2: Foundational (T001-T007)
2. Complete Phase 3: User Story 1 (T008-T017)
3. **STOP and VALIDATE**: Test product selection and systems display
4. Run `./gradlew build` to verify
5. Deploy/demo if ready

### Incremental Delivery

1. Complete Foundational -> Foundation ready
2. Add User Story 1 -> Test independently -> Deploy/Demo (MVP!)
3. Add User Story 2 -> Test search functionality -> Deploy/Demo
4. Add User Story 3 -> Test export functionality -> Deploy/Demo
5. Polish phase for final cleanup

### Recommended Single Developer Flow

```
T001-T007 (Foundational) -> T008-T017 (US1) -> VALIDATE MVP
                         -> T018-T024 (US2) -> VALIDATE SEARCH
                         -> T025-T028 (US3) -> VALIDATE EXPORT
                         -> T029-T033 (Polish) -> DONE
```

---

## Notes

- [P] tasks = different files, no dependencies
- [Story] label maps task to specific user story for traceability
- Each user story is independently testable after completion
- No test tasks included (per constitution Principle IV: User-Requested Testing)
- Commit after each task or logical group
- Stop at any checkpoint to validate story independently
- Access control uses existing AssetFilterService pattern
- All @Secured annotations use roles: ADMIN, VULN, SECCHAMPION
