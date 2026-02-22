# Session Context

## User Prompts

### Prompt 1

Implement the following plan:

# Plan: Default CrowdStrike UI Lookups to HIGH+CRITICAL Severity

## Context
When searching for system vulnerabilities via the CrowdStrike Vulnerability Lookup page (`/vulnerabilities/system`), the severity filter is never sent to the backend — the log shows `severity=null`. This causes the query to return **all** severities (including Low, Medium, Informational), which is not what users want. The user always wants to search for **HIGH and CRITICAL** vulnerabilit...

### Prompt 2

currently there is a limit of 100 findings, please increase this to 20000

### Prompt 3

[Image: source: REDACTED 2026-02-22 at 20.31.40.png]

### Prompt 4

i still see the limit of 100, please reevaluate how to change the limit to 20000

### Prompt 5

[Image: source: REDACTED 2026-02-22 at 20.33.46.png]

### Prompt 6

[Request interrupted by user for tool use]

