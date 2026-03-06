# Session Context

## User Prompts

### Prompt 1

Implement the following plan:

# Fix JWT `alg:none` — Unsigned Token Vulnerability

## Context

JWT tokens are being generated with `{"alg":"none"}` (unsigned). Screenshot evidence confirms tokens have no signature. This is a **critical security vulnerability** — anyone can forge valid tokens.

**Root cause**: `application.yml` has TWO `micronaut:` top-level keys (line 1 and line 138). In YAML, duplicate keys cause the last value to silently overwrite the first. The second block (line 138, c...

### Prompt 2

Tool loaded.

### Prompt 3

Tool loaded.

