# CrowdStrike Authentication API Contract

**Feature**: 023-create-in-the  
**API**: CrowdStrike OAuth2 Token Service  
**Base URL**: `https://api.crowdstrike.com`

## Overview

This contract defines the authentication flow for obtaining OAuth2 access tokens from the CrowdStrike Falcon API. The CLI must authenticate before making any vulnerability queries.

---

## Endpoint: Obtain Access Token

**Method**: `POST`  
**Path**: `/oauth2/token`  
**Content-Type**: `application/x-www-form-urlencoded`

### Request

**Headers**:
```
Content-Type: application/x-www-form-urlencoded
Accept: application/json
```

**Body Parameters** (URL-encoded):
```
client_id={CLIENT_ID}&client_secret={CLIENT_SECRET}&grant_type=client_credentials
```

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `client_id` | string | Yes | OAuth2 client ID from config |
| `client_secret` | string | Yes | OAuth2 client secret from config |
| `grant_type` | string | Yes | Must be "client_credentials" |

**Example Request**:
```http
POST /oauth2/token HTTP/1.1
Host: api.crowdstrike.com
Content-Type: application/x-www-form-urlencoded
Accept: application/json

client_id=abc123xyz&client_secret=secret456&grant_type=client_credentials
```

---

### Response: Success (200 OK)

**Status Code**: `200 OK`  
**Content-Type**: `application/json`

**Body**:
```json
{
  "access_token": "eyJhbGciOiJSUzI1NiIsImtpZCI6InB1YmxpYzo...",
  "token_type": "bearer",
  "expires_in": 1799
}
```

| Field | Type | Description |
|-------|------|-------------|
| `access_token` | string | JWT bearer token for API requests |
| `token_type` | string | Token type (always "bearer") |
| `expires_in` | integer | Token lifetime in seconds (typically 1799 = ~30 min) |

**Mapping to Domain Model**:
```kotlin
AuthToken(
    accessToken = response.access_token,
    expiresAt = Instant.now().plusSeconds(response.expires_in),
    tokenType = response.token_type
)
```

---

### Response: Authentication Failed (401 Unauthorized)

**Status Code**: `401 Unauthorized`  
**Content-Type**: `application/json`

**Body**:
```json
{
  "errors": [
    {
      "code": 401,
      "message": "access denied, authorization failed"
    }
  ]
}
```

**Error Handling**:
- Do NOT retry (authentication errors are not transient)
- User message: "Failed to authenticate with CrowdStrike API. Please verify your credentials in ~/.secman/crowdstrike.conf"
- Exit code: 1

---

### Response: Invalid Request (400 Bad Request)

**Status Code**: `400 Bad Request`  
**Content-Type**: `application/json`

**Body**:
```json
{
  "errors": [
    {
      "code": 400,
      "message": "invalid grant_type"
    }
  ]
}
```

**Error Handling**:
- Do NOT retry
- User message: "Invalid authentication request. Contact support."
- Exit code: 1

---

### Response: Rate Limit (429 Too Many Requests)

**Status Code**: `429 Too Many Requests`  
**Content-Type**: `application/json`

**Headers**:
```
X-RateLimit-Limit: 600
X-RateLimit-Remaining: 0
Retry-After: 60
```

**Body**:
```json
{
  "errors": [
    {
      "code": 429,
      "message": "rate limit exceeded"
    }
  ]
}
```

**Error Handling**:
- Retry with exponential backoff (respect Retry-After header)
- User message: "Rate limit exceeded during authentication. Retrying in {delay} seconds..."
- Max retries: 5

---

### Response: Server Error (500 Internal Server Error)

**Status Code**: `500 Internal Server Error`  
**Content-Type**: `application/json`

**Body**:
```json
{
  "errors": [
    {
      "code": 500,
      "message": "internal server error"
    }
  ]
}
```

**Error Handling**:
- Retry with exponential backoff
- User message: "CrowdStrike API error during authentication. Retrying..."
- Max retries: 3

---

## Token Usage

Once obtained, the access token must be included in all subsequent API requests:

**Header**:
```
Authorization: Bearer {access_token}
```

**Token Refresh Strategy**:
- Check token expiration before each API request
- If token expires within 60 seconds, proactively refresh
- On 401 response during API call, invalidate token and re-authenticate

---

## Security Requirements

1. **Never log credentials or tokens**:
   - Do NOT log `client_id`, `client_secret`, or `access_token`
   - Log only: "Authenticating with CrowdStrike API..." (INFO level)

2. **HTTPS only**:
   - All requests MUST use HTTPS (verify TLS certificates)

3. **Token storage**:
   - Store token in memory only (never persist to disk)
   - Clear token on CLI exit

4. **Config file security**:
   - Read credentials from file with 600/400 permissions only
   - Refuse to load if permissions are too open

---

## Test Cases

### Contract Test 1: Successful Authentication
```kotlin
@Test
fun `should authenticate and return valid token`() {
    // Mock API response
    mockServer.enqueue(MockResponse()
        .setResponseCode(200)
        .setBody("""{"access_token":"token123","token_type":"bearer","expires_in":1799}""")
    )
    
    val authService = AuthService(...)
    val token = authService.authenticate("clientId", "clientSecret")
    
    assertThat(token.accessToken).isEqualTo("token123")
    assertThat(token.isExpired()).isFalse()
}
```

### Contract Test 2: Authentication Failure
```kotlin
@Test
fun `should throw exception on 401 Unauthorized`() {
    mockServer.enqueue(MockResponse()
        .setResponseCode(401)
        .setBody("""{"errors":[{"code":401,"message":"access denied"}]}""")
    )
    
    val authService = AuthService(...)
    
    assertThrows<AuthenticationException> {
        authService.authenticate("invalid", "credentials")
    }
}
```

### Contract Test 3: Rate Limit Retry
```kotlin
@Test
fun `should retry on 429 with exponential backoff`() {
    mockServer.enqueue(MockResponse()
        .setResponseCode(429)
        .setHeader("Retry-After", "2")
        .setBody("""{"errors":[{"code":429,"message":"rate limit exceeded"}]}""")
    )
    mockServer.enqueue(MockResponse()
        .setResponseCode(200)
        .setBody("""{"access_token":"token123","token_type":"bearer","expires_in":1799}""")
    )
    
    val authService = AuthService(...)
    val token = authService.authenticate("clientId", "clientSecret")
    
    assertThat(token).isNotNull()
    assertThat(mockServer.requestCount).isEqualTo(2)
}
```

---

## References

- [CrowdStrike OAuth2 Documentation](https://falcon.crowdstrike.com/documentation/46/crowdstrike-oauth2-based-apis)
