# Quickstart: CLI Query Clients/Workstations

**Feature**: 055-cli-query-clients

## Usage

### Query Workstations

```bash
./gradlew cli:run --args='query servers --device-type WORKSTATION severity CRITICAL,HIGH --min-days-open 1'
```

### Query Servers (default, unchanged)

```bash
./gradlew cli:run --args='query servers severity CRITICAL,HIGH --min-days-open 1'
```

### Query All Devices

```bash
./gradlew cli:run --args='query servers --device-type ALL severity CRITICAL,HIGH --min-days-open 1'
```

### Import Workstation Vulnerabilities

```bash
./gradlew cli:run --args='query servers --device-type WORKSTATION severity CRITICAL,HIGH --min-days-open 1 --save --username admin --password secret --backend-url http://localhost:8080'
```

## New Option

| Option | Values | Default | Description |
|--------|--------|---------|-------------|
| `--device-type` | SERVER, WORKSTATION, ALL | SERVER | Filter by CrowdStrike device classification |

## Verification

1. **Dry run workstation query**:
   ```bash
   ./gradlew cli:run --args='query servers --device-type WORKSTATION --dry-run --verbose'
   ```

2. **Check output includes workstation count**:
   ```
   Querying CrowdStrike for workstations...
   Found X vulnerabilities across workstations
   ```

3. **Verify server default unchanged**:
   ```bash
   ./gradlew cli:run --args='query servers --verbose'
   ```
   Should show "device type=SERVER" in filters.

## Build

```bash
./gradlew build
```

No additional dependencies or configuration required.
