# Session Context

## User Prompts

### Prompt 1

Implement the following plan:

# Fix: CLI Startup Triggers Backend Migration Services

## Context

Running `./bin/secman manage-user-mappings list-bucket` boots the **full Micronaut application context** — including Flyway, Hibernate, HikariCP, and 4 backend startup listeners — even though the command only needs `S3DownloadService`. The `RequirementIdMigrationService` fires @Async on a background thread; by the time it executes, the `.use { }` block has already closed the context, causing a ...

