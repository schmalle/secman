# Codebase Structure

## Directory Layout
```
src/
├── backend/           # Play Framework Scala backend
│   ├── app/
│   │   ├── models/    # JPA entities (User, Requirement, UseCase, etc.)
│   │   ├── controllers/ # REST API controllers
│   │   ├── services/  # Business logic services
│   │   └── actions/   # Security/authentication actions
│   ├── conf/          # Configuration and routes
│   │   ├── application.conf
│   │   ├── routes
│   │   └── evolutions/default/ # Database migrations
│   └── test/          # Backend tests
└── frontend/          # Astro + React frontend
    ├── src/
    │   ├── components/ # React components (.tsx files)
    │   ├── pages/     # Astro pages (.astro files)
    │   ├── layouts/   # Astro layouts
    │   └── utils/     # Utility functions
    ├── public/        # Static assets
    └── tests/         # Playwright tests
```

## Key Entity Files
- **Backend Models**: `src/backend/app/models/` (UseCase.java, etc.)
- **Backend Controllers**: `src/backend/app/controllers/` (UseCaseController.java, etc.)
- **Frontend Components**: `src/frontend/src/components/` (UseCaseManagement.tsx, etc.)
- **Frontend Pages**: `src/frontend/src/pages/` (usecases.astro, etc.)