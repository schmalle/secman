# e2e-runner.config.json — Schema Reference

Place this file in your **project root**. All fields are optional — the skill
uses sensible defaults for a Kotlin/Micronaut + Astro/React stack.

```jsonc
{
  // ── Backend ────────────────────────────────────────────────
  "backend": {
    // Shell command to start the backend.
    // Wrap with "op run --" if you need 1Password secrets injection.
    "start": "gradle :backendng:clean :backendng:run",

    // URL the health-check pings to confirm the backend is ready.
    "healthUrl": "http://localhost:8080",

    // How many seconds to wait before declaring the backend dead.
    "healthTimeout": 120,

    // Optional: if true, appends --no-daemon to the Gradle invocation.
    "noDaemon": false,

    // Optional: working directory relative to project root.
    "cwd": "."
  },

  // ── Frontend ───────────────────────────────────────────────
  "frontend": {
    "start": "npm run dev",
    "healthUrl": "http://localhost:4321",
    "healthTimeout": 60,

    // Optional: working directory relative to project root
    // (useful for monorepos where the frontend is in a subdirectory).
    "cwd": "./frontend"
  },

  // ── E2E Tests ──────────────────────────────────────────────
  "e2e": {
    // Path to the shell script that runs the E2E suite.
    "script": "./scripts/e2e-test.sh",

    // Maximum number of fix-and-retry iterations.
    "maxRetries": 5,

    // Seconds to wait between retries (lets hot-reload settle).
    "retryDelay": 5,

    // Optional: environment variables to inject into the test process.
    "env": {
      "BASE_URL": "http://localhost:4321",
      "API_URL": "http://localhost:8080"
    }
  }
}
```

## E2E Environment Variables

| Variable             | Default                 | Description                                                      |
| -------------------- | ----------------------- | ---------------------------------------------------------------- |
| `BASE_URL`           | `http://localhost:4321` | Frontend URL for smoke tests                                     |
| `API_URL`            | `http://localhost:8080` | Backend URL for smoke tests                                      |
| `SECMAN_BACKEND_URL` | Same as `BASE_URL`      | Target URL for JS error scanner (overrides 1Password default)    |
| `SECMAN_INSECURE`    | `false`                 | Accept self-signed SSL certs (for non-localhost targets)          |

**Note:** `SECMAN_ADMIN_NAME` and `SECMAN_ADMIN_PASS` are resolved by 1Password
inside `tests/js-error-scanner.sh` via `op run`. They do not need to be set
in the config — the scanner handles credential injection automatically.

## Minimal Example

If your project follows the standard layout, you only need:

```json
{}
```

All defaults will apply. Override only what differs.

## 1Password Integration

If your backend needs secrets from 1Password:

```json
{
  "backend": {
    "start": "op run --env-file=secman.env -- gradle :backendng:clean :backendng:run"
  }
}
```

This uses `op run` to inject secrets into the Gradle process at startup.
