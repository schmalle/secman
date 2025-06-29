# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Secman is a requirement and risk assessment management tool with a full-stack architecture:

- **Backend**: Play Framework (Scala) API server with MariaDB database
- **Frontend**: Astro static site generator with React components
- **Database**: MariaDB with JPA/Hibernate ORM and Play evolutions

The system manages Users, Requirements, Standards, Norms, Use Cases, Assets, Risks, and Risk Assessments with role-based authentication (normaluser/adminuser).

## Development Commands

### Backend (Play Framework)
```bash
cd src/backend
sbt compile                    # Compile the backend
sbt run                       # Start backend server (port 9000)
sbt test                      # Run tests
sbt dependencyCheckAnalyze    # Security dependency analysis
```

### Frontend (Astro + React)
```bash
cd src/frontend
npm install                   # Install dependencies
npm run dev                   # Start dev server (port 4321)
npm run build                 # Build for production
npm run preview               # Preview production build
```

## Architecture

### API Structure
- RESTful API with `/api/` prefix
- Authentication required for all endpoints except auth routes
- CORS configured for localhost:4321 frontend
- Session-based authentication with role-based access control

### Database
- MariaDB database named "secman" 
- JPA entities in `src/backend/app/models/`
- Database evolutions in `src/backend/conf/evolutions/default/`
- Connection configured in `application.conf`

### Frontend Components
- React components in `src/frontend/src/components/`
- Astro pages in `src/frontend/src/pages/`
- Bootstrap 5 for styling with Bootstrap Icons
- Axios for API communication

### Key Entities and Relationships
- Users have roles (normaluser/adminuser)
- Requirements belong to Standards and can have associated Risks
- Assets can have multiple Risks and Risk Assessments
- Risk Assessments evaluate specific Risks for specific Assets

### Design Patterns
- UI design as found in /requirements (button styles, form layouts) must be consistent across components

## Configuration Notes

- Backend runs on port 9000, frontend on port 4321
- Database credentials in `application.conf` (default: secman/CHANGEME)
- CSRF protection disabled for API routes
- File upload support for Excel/XLSX import functionality
- Export functionality for requirements to DOCX format


if user asks to commit code then please always also push the code to github.
