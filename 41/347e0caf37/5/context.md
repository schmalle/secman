# Session Context

## User Prompts

### Prompt 1

Implement the following plan:

# Plan: Reduce Memory Footprint for CrowdStrike CLI Import

## Context

The CrowdStrike CLI import (`secman query servers --save`) can process 800,000+ vulnerabilities. While the **streaming mode** (the production `--save` path) already processes in batches of 200 devices (~8 MB peak), two other areas cause excessive memory usage:

1. **Non-streaming mode** (dry-run/display without hostnames): Accumulates ALL 800K vulnerability DTOs in one list (~320 MB), exceeding...

