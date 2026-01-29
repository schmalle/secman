# Quickstart: Enhanced Admin Summary Email

**Feature**: 069-enhanced-admin-summary
**Date**: 2026-01-28

## Prerequisites

- Kotlin/Java backend builds successfully: `./gradlew build`
- CLI shadow JAR built: `./gradlew :cli:shadowJar`
- MariaDB running with vulnerability data populated

## Files to Modify

1. **`src/backendng/src/main/kotlin/com/secman/service/AdminSummaryService.kt`**
   - Extend `SystemStatistics` data class with `vulnerabilityStatisticsUrl`, `topProducts`, `topServers`
   - Add `ProductSummary` and `ServerSummary` inner data classes
   - Inject `AppConfig` and `VulnerabilityStatisticsService`
   - Update `getSystemStatistics()` to gather top-10 data
   - Update `renderHtmlTemplate()` and `renderTextTemplate()` with new template variables

2. **`src/backendng/src/main/resources/email-templates/admin-summary.html`**
   - Add call-to-action button linking to vulnerability statistics page
   - Add HTML table for top 10 most affected products
   - Add HTML table for top 10 most affected servers
   - Handle empty state with "No vulnerability data available" message

3. **`src/backendng/src/main/resources/email-templates/admin-summary.txt`**
   - Add vulnerability statistics URL as plain text
   - Add ASCII-formatted top 10 products list
   - Add ASCII-formatted top 10 servers list
   - Handle empty state message

4. **`src/cli/src/main/kotlin/com/secman/cli/commands/SendAdminSummaryCommand.kt`**
   - Update dry-run and verbose output to display top-10 data and the link URL

## Verification

```bash
# Build the project
./gradlew build

# Build CLI JAR
./gradlew :cli:shadowJar

# Test with dry-run (no emails sent)
./bin/secmanng send-admin-summary --dry-run --verbose

# Expected output should now include:
# - Vulnerability Statistics URL
# - Top 10 Most Affected Products (name + count)
# - Top 10 Most Affected Servers (name + count)
```

## Implementation Order

1. Extend `SystemStatistics` and add data classes (service layer)
2. Inject dependencies and update `getSystemStatistics()` method
3. Update HTML template with link button and top-10 tables
4. Update plain-text template with link URL and top-10 lists
5. Update template rendering methods with new variable replacements
6. Update CLI command output for dry-run preview
7. Build and verify with `./gradlew build`
8. Test end-to-end with `./bin/secmanng send-admin-summary --dry-run --verbose`
