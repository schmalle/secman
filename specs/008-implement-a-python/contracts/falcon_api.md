# Falcon API Contract

## Overview
This contract defines the expected behavior of interactions with the CrowdStrike Falcon Spotlight Vulnerabilities API via the falconpy library.

## Authentication

**Method**: OAuth2 Client Credentials Flow

**Implementation**:
```python
from falconpy import SpotlightVulnerabilities

falcon = SpotlightVulnerabilities(
    client_id=os.environ['FALCON_CLIENT_ID'],
    client_secret=os.environ['FALCON_CLIENT_SECRET'],
    base_url=cloud_region_to_url(os.environ['FALCON_CLOUD_REGION'])
)
```

**Success Response**: HTTP 200 with access token (handled internally by falconpy)
**Error Response**: HTTP 401 with error details → Exit code 1

---

## Query Vulnerabilities (Combined)

**Endpoint**: `queryVulnerabilitiesCombined()`
**Purpose**: Retrieve vulnerability data with device information in single paginated call

### Request Parameters

```python
{
    "filter": str,  # FQL filter string
    "limit": int,   # Page size (max 5000)
    "offset": int,  # Pagination offset
    "sort": str     # Sort specification (optional)
}
```

**Filter Construction** (Falcon Query Language):
```python
# Device type filter
"platform_name:*'Workstation'"  # CLIENT
"platform_name:*'Server'"       # SERVER
# No filter for BOTH

# Severity filter (OR logic)
"cve.severity:['CRITICAL','HIGH']"

# Days open filter
"created_timestamp:<'2024-10-01T00:00:00Z'"  # Calculate from min_days_open

# AD domain filter
"host.ad_domain:'CORP.LOCAL'"

# Hostname filter
"host.hostname:'WEB-SERVER-01'"

# Combined with AND logic
"platform_name:*'Server'+cve.severity:['CRITICAL']+created_timestamp:<'2024-10-01T00:00:00Z'"
```

### Success Response (HTTP 200)

```json
{
  "status_code": 200,
  "body": {
    "meta": {
      "pagination": {
        "total": 1234,
        "limit": 500,
        "offset": 0
      }
    },
    "resources": [
      {
        "id": "vuln_12345",
        "cve": {
          "id": "CVE-2021-44228",
          "severity": "CRITICAL",
          "cvss_score": 10.0,
          "description": "Log4j RCE vulnerability"
        },
        "host": {
          "hostname": "WEB-SERVER-01",
          "local_ip": "10.100.200.1",
          "groups": ["SVR-WEB-DMZ"],
          "cloud_provider_account_id": null,
          "instance_id": null,
          "os_version": "Windows Server 2019",
          "ad_domain": "CORP.LOCAL",
          "platform_name": "Windows"
        },
        "apps": {
          "product_name_version": "Apache Log4j 2.14.1"
        },
        "created_timestamp": "2024-09-05T08:30:00Z"
      }
    ]
  }
}
```

### Error Responses

**401 Unauthorized**:
```json
{
  "status_code": 401,
  "body": {
    "errors": [
      {
        "code": 401,
        "message": "Invalid authentication credentials"
      }
    ]
  }
}
```
→ Exit code 1

**429 Too Many Requests**:
```json
{
  "status_code": 429,
  "body": {
    "errors": [
      {
        "code": 429,
        "message": "Rate limit exceeded"
      }
    ]
  },
  "headers": {
    "X-RateLimit-RetryAfter": "60"
  }
}
```
→ Retry with exponential backoff, max 5 retries → Exit code 4 if exhausted

**500/502/503/504 Server Errors**:
→ Retry with exponential backoff → Exit code 2 (network error) or 4 (API error)

---

## Pagination Contract

**Behavior**: Offset-based pagination

**Algorithm**:
```python
all_results = []
offset = 0
limit = 500  # Optimal page size

while True:
    response = falcon.queryVulnerabilitiesCombined(
        filter=fql_string,
        limit=limit,
        offset=offset
    )

    resources = response['body']['resources']
    all_results.extend(resources)

    total = response['body']['meta']['pagination']['total']

    if offset + limit >= total:
        break  # All records retrieved

    offset += limit
```

**Expected Behavior**:
- No maximum result limit (clarification confirmed)
- Progress indication starts after 10 seconds
- Each page request subject to retry logic

---

## Timeout Configuration

**Connect Timeout**: 10 seconds
**Read Timeout**: 30 seconds

```python
falcon = SpotlightVulnerabilities(
    client_id=...,
    client_secret=...,
    timeout=(10, 30)  # (connect, read)
)
```

---

## TLS Certificate Validation

**Requirement**: MUST validate TLS certificates (constitutional requirement)
**Implementation**: Default falconpy behavior (uses `requests` library defaults)
**Override**: NEVER set `verify=False`

---

## Retry Logic Contract

**Retry Conditions**:
- HTTP 429 (rate limit)
- HTTP 502, 503, 504 (server errors)
- Connection timeout
- Read timeout

**Backoff Strategy**:
```python
retries = 0
max_retries = 5
base_delay = 1.0  # seconds

while retries < max_retries:
    try:
        response = falcon.queryVulnerabilitiesCombined(...)
        return response
    except (RateLimitError, ServerError, TimeoutError):
        retries += 1
        if retries >= max_retries:
            raise  # Exit code 4
        delay = base_delay * (2 ** retries)  # Exponential: 1, 2, 4, 8, 16 seconds
        time.sleep(delay)
```

**Total Max Wait**: ~31 seconds (1+2+4+8+16)

---

## Contract Test Coverage

**Tests to Implement** (must FAIL before implementation):

1. `test_authentication_success()` - Valid credentials return 200
2. `test_authentication_invalid_credentials()` - Invalid credentials return 401, exit code 1
3. `test_query_returns_expected_schema()` - Response matches contract structure
4. `test_pagination_retrieves_all_records()` - Multiple pages correctly concatenated
5. `test_rate_limit_triggers_retry()` - 429 response triggers exponential backoff
6. `test_rate_limit_exhausted_exits_4()` - Max retries exceeded returns exit code 4
7. `test_server_error_triggers_retry()` - 503 response triggers retry
8. `test_network_timeout_exits_2()` - Connection timeout exits with code 2
9. `test_tls_validation_enforced()` - Certificate errors are not ignored

---

## Notes

- All API interactions MUST go through falconpy (constitutional requirement VI)
- Credentials NEVER logged at any level (constitutional security requirement)
- Sanitize API responses before DEBUG logging (remove any sensitive data patterns)
