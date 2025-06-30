# Code Style and Conventions

## Backend (Scala/Java)
- **Language**: Scala with Play Framework
- **Models**: Java JPA entities with annotations
- **Controllers**: Scala classes extending BaseController
- **Naming**: CamelCase for classes, camelCase for methods
- **Package Structure**: models, controllers, services, actions

## Frontend (TypeScript/React)
- **Language**: TypeScript with React components
- **Component Files**: `.tsx` extension for React components
- **Page Files**: `.astro` extension for Astro pages
- **Styling**: Bootstrap 5 with Bootstrap Icons
- **API Communication**: Axios for HTTP requests
- **Naming**: PascalCase for components, camelCase for variables

## Database
- **ORM**: JPA/Hibernate with MariaDB
- **Migrations**: Play evolutions in `conf/evolutions/default/`
- **Naming**: snake_case for database tables/columns

## UI Design Patterns
- Bootstrap 5 component patterns as defined in `/requirements`
- Consistent button styles and form layouts across components
- Role-based UI rendering (normaluser vs adminuser)

## Configuration
- Backend config: `src/backend/conf/application.conf`
- Frontend config: `astro.config.mjs`, `package.json`
- CORS configured for localhost:4321 ↔ localhost:9000 communication