# Data Model: JavaScript Error Scanner

**Branch**: `083-js-error-scanner` | **Date**: 2026-03-20

## Overview

This feature has no persistent data model. The scanner operates entirely in-memory during execution. The following describes the runtime data structures used within the Node.js script.

## Runtime Structures

### PageResult

Represents the scan result for a single page visit.

- **uri**: The page path visited (e.g., `/assets`, `/admin/user-management`)
- **status**: Outcome of the visit — `clean`, `errors`, or `timeout`
- **uncaughtExceptions**: List of uncaught JavaScript exception messages captured via `pageerror` event
- **consoleErrors**: List of `console.error` message texts captured via `console` event
- **loadTimeMs**: Time in milliseconds from navigation start to `networkidle`

### ScanReport

Aggregated results after visiting all pages.

- **host**: The secman instance URL scanned
- **totalPages**: Count of pages visited
- **cleanPages**: Count of pages with no errors
- **errorPages**: Count of pages with at least one error (uncaught or console)
- **timeoutPages**: Count of pages that timed out before `networkidle`
- **results**: Ordered list of PageResult entries
- **durationMs**: Total scan duration

## Relationships

```text
ScanReport 1──* PageResult
PageResult 1──* uncaughtExceptions (strings)
PageResult 1──* consoleErrors (strings)
```

## Notes

- No database tables, no file persistence, no API contracts.
- Data exists only for the duration of the script execution and is printed to stdout as the final report.
