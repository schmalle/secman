# Project Overview

## Secman - Security Management Tool

Secman is a requirement and risk assessment management tool with a full-stack architecture:

### Architecture
- **Backend**: Play Framework (Scala) API server with MariaDB database
- **Frontend**: Astro static site generator with React components  
- **Database**: MariaDB with JPA/Hibernate ORM and Play evolutions

### Key Features
The system manages:
- Users with role-based authentication (normaluser/adminuser)
- Requirements, Standards, Norms
- Use Cases, Assets, Risks, and Risk Assessments
- Import/Export functionality for requirements (DOCX/XLSX)

### Tech Stack
- **Backend**: Play Framework (Scala), MariaDB, JPA/Hibernate
- **Frontend**: Astro, React, Bootstrap 5, Bootstrap Icons, Axios
- **Build Tools**: SBT (backend), npm (frontend)
- **Testing**: Playwright (frontend)

### Ports
- Backend: 9000
- Frontend: 4321