# Backend Quick Start

```bash
# Start the backend (from repository root — outside any sandbox, pass-cli required)
./scripts/startbackenddev.sh

# CLI usage (from repository root)
./gradlew :cli:shadowJar
./scripts/secman query servers --severity HIGH --min-days-open 1 --save

# Set backend URL for remote use
export SECMAN_BACKEND_URL=https://api.yourdomain.com
```

See [docs/CLI.md](../../../docs/CLI.md) for full CLI reference.
