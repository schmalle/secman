# Suggested Development Commands

## Backend (Play Framework)
```bash
cd src/backend
sbt compile                    # Compile the backend
sbt run                       # Start backend server (port 9000)
sbt test                      # Run tests
sbt dependencyCheckAnalyze    # Security dependency analysis
```

## Frontend (Astro + React)
```bash
cd src/frontend
npm install                   # Install dependencies
npm run dev                   # Start dev server (port 4321)
npm run build                 # Build for production
npm run preview               # Preview production build
```

## Frontend Testing
```bash
cd src/frontend
npm run test                  # Run Playwright tests
npm run test:ui               # Run tests with UI
npm run test:headed           # Run tests in headed mode
npm run test:checkin          # Run checkin tests
npm run test:e2e              # Run end-to-end tests
npm run test:all              # Run all tests
```

## System Commands (macOS)
- `ls` - List directory contents
- `cd` - Change directory
- `find` - Find files
- `grep` - Search text patterns
- `git` - Git operations