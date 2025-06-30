# Task Completion Checklist

## After Making Code Changes

### Backend Changes
1. **Compile**: `cd src/backend && sbt compile`
2. **Test**: `cd src/backend && sbt test`
3. **Security Check**: `cd src/backend && sbt dependencyCheckAnalyze`

### Frontend Changes  
1. **Build**: `cd src/frontend && npm run build`
2. **Test**: `cd src/frontend && npm run test:all`
3. **Type Check**: Ensure TypeScript compilation succeeds

### Database Changes
- If model changes: Check database evolutions in `conf/evolutions/default/`
- Restart backend to apply migrations if needed

### Git Workflow
- **Commit**: Create descriptive commit messages
- **Push**: Always push to GitHub after committing (as per CLAUDE.md instructions)

### Integration Testing
- Start both backend (`sbt run`) and frontend (`npm run dev`)
- Verify API communication works between ports 9000 ↔ 4321
- Test role-based access control if applicable

### Documentation
- Update relevant documentation if API or component interfaces change
- Follow existing patterns for consistency