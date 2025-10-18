# Contract: Access Denial Logging Specification

**Feature**: 025-role-based-access-control | **Date**: 2025-10-18
**Related**: [../spec.md](../spec.md) | [../data-model.md](../data-model.md) | [./role-permission-matrix.md](./role-permission-matrix.md)

## Overview

This contract defines the logging specification for access denial events. All 403 Forbidden responses triggered by @Secured annotations MUST be logged with structured context for security audit purposes.

**Constitutional Reference**: Principle I (Security-First) - FR-014: "System MUST log all access denials with full context"

---

## Logging Service

### AccessDenialLogger

**File**: `/Users/flake/sources/misc/secman/src/backendng/src/main/kotlin/com/secman/service/AccessDenialLogger.kt`

**Purpose**: Centralized service for logging access denial events with MDC context

**Full Implementation**:

```kotlin
package com.secman.service

import io.micronaut.security.authentication.Authentication
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import java.time.Instant

/**
 * Service for logging access denials for security audit purposes
 * Feature: 025-role-based-access-control
 *
 * Logs are written to a dedicated logger (ACCESS_DENIAL_AUDIT) with structured MDC context.
 * This enables easy aggregation in log management systems (Splunk, ELK, Datadog).
 *
 * Usage:
 * ```
 * accessDenialLogger.logAccessDenial(
 *     authentication = authentication,
 *     resource = request.uri.path,
 *     requiredRoles = listOf("ADMIN", "RISK"),
 *     ipAddress = request.remoteAddress.hostString
 * )
 * ```
 *
 * Log Output Example:
 * ```
 * 2025-10-18 14:23:45.123 WARN [ACCESS_DENIAL_AUDIT] Access denied: user='john.doe',
 * roles=[USER,REQ], resource='/api/risk-assessments', required=[ADMIN,RISK,SECCHAMPION],
 * ip='192.168.1.100' event_type=access_denied user_id=john.doe user_roles=USER,REQ
 * resource=/api/risk-assessments required_roles=ADMIN,RISK,SECCHAMPION
 * timestamp=2025-10-18T14:23:45.123Z ip_address=192.168.1.100
 * ```
 *
 * Constitutional Compliance: Principle I (Security-First) - FR-014
 */
@Singleton
class AccessDenialLogger {

    companion object {
        /**
         * Dedicated logger for access denial events
         * Allows separate log routing/filtering in log aggregation systems
         */
        private val log = LoggerFactory.getLogger("ACCESS_DENIAL_AUDIT")

        // MDC keys (constants for consistency)
        private const val MDC_EVENT_TYPE = "event_type"
        private const val MDC_USER_ID = "user_id"
        private const val MDC_USER_ROLES = "user_roles"
        private const val MDC_RESOURCE = "resource"
        private const val MDC_REQUIRED_ROLES = "required_roles"
        private const val MDC_TIMESTAMP = "timestamp"
        private const val MDC_IP_ADDRESS = "ip_address"
        private const val MDC_HTTP_METHOD = "http_method"
        private const val MDC_USER_AGENT = "user_agent"
    }

    /**
     * Log an access denial event with full context
     *
     * @param authentication Authenticated user attempting access
     * @param resource Resource path attempted (e.g., "/api/risk-assessments")
     * @param requiredRoles Roles required to access the resource
     * @param ipAddress Optional IP address of the request
     * @param userAgent Optional User-Agent header for device/browser tracking
     */
    fun logAccessDenial(
        authentication: Authentication,
        resource: String,
        requiredRoles: List<String>,
        ipAddress: String? = null,
        userAgent: String? = null
    ) {
        try {
            // Add structured context to MDC for log aggregation
            MDC.put(MDC_EVENT_TYPE, "access_denied")
            MDC.put(MDC_USER_ID, authentication.name)
            MDC.put(MDC_USER_ROLES, authentication.roles.joinToString(","))
            MDC.put(MDC_RESOURCE, resource)
            MDC.put(MDC_REQUIRED_ROLES, requiredRoles.joinToString(","))
            MDC.put(MDC_TIMESTAMP, Instant.now().toString())

            // Optional fields
            ipAddress?.let { MDC.put(MDC_IP_ADDRESS, it) }
            userAgent?.let { MDC.put(MDC_USER_AGENT, it) }

            // Log at WARN level (not ERROR - this is expected behavior for unauthorized access)
            log.warn(
                "Access denied: user='{}', roles=[{}], resource='{}', required=[{}], ip='{}', agent='{}'",
                authentication.name,
                authentication.roles.joinToString(","),
                resource,
                requiredRoles.joinToString(","),
                ipAddress ?: "unknown",
                userAgent?.take(100) ?: "unknown" // Truncate user-agent to 100 chars
            )
        } catch (e: Exception) {
            // Log failure to log (ironic but necessary)
            log.error("Failed to log access denial: {}", e.message, e)
        } finally {
            // CRITICAL: Always clear MDC to prevent context leakage across threads
            // Micronaut uses thread pools, so MDC can bleed between requests if not cleared
            MDC.clear()
        }
    }

    /**
     * Log an access denial with HTTP method context
     *
     * @param authentication Authenticated user
     * @param httpMethod HTTP method (GET, POST, PUT, DELETE, etc.)
     * @param resource Resource path
     * @param requiredRoles Required roles
     * @param ipAddress Optional IP address
     * @param userAgent Optional User-Agent header
     */
    fun logAccessDenialWithMethod(
        authentication: Authentication,
        httpMethod: String,
        resource: String,
        requiredRoles: List<String>,
        ipAddress: String? = null,
        userAgent: String? = null
    ) {
        try {
            MDC.put(MDC_EVENT_TYPE, "access_denied")
            MDC.put(MDC_USER_ID, authentication.name)
            MDC.put(MDC_USER_ROLES, authentication.roles.joinToString(","))
            MDC.put(MDC_HTTP_METHOD, httpMethod)
            MDC.put(MDC_RESOURCE, resource)
            MDC.put(MDC_REQUIRED_ROLES, requiredRoles.joinToString(","))
            MDC.put(MDC_TIMESTAMP, Instant.now().toString())

            ipAddress?.let { MDC.put(MDC_IP_ADDRESS, it) }
            userAgent?.let { MDC.put(MDC_USER_AGENT, it) }

            log.warn(
                "Access denied: user='{}', method={}, resource='{}', roles=[{}], required=[{}], ip='{}'",
                authentication.name,
                httpMethod,
                resource,
                authentication.roles.joinToString(","),
                requiredRoles.joinToString(","),
                ipAddress ?: "unknown"
            )
        } catch (e: Exception) {
            log.error("Failed to log access denial with method: {}", e.message, e)
        } finally {
            MDC.clear()
        }
    }

    /**
     * Log a summary of access denials for a user (batch logging)
     * Useful for detecting brute-force attempts or reconnaissance
     *
     * @param username Username attempting access
     * @param denialCount Number of denials in time window
     * @param timeWindowMinutes Time window for counting denials
     */
    fun logAccessDenialSummary(
        username: String,
        denialCount: Int,
        timeWindowMinutes: Int
    ) {
        try {
            MDC.put(MDC_EVENT_TYPE, "access_denial_summary")
            MDC.put(MDC_USER_ID, username)
            MDC.put("denial_count", denialCount.toString())
            MDC.put("time_window_minutes", timeWindowMinutes.toString())
            MDC.put(MDC_TIMESTAMP, Instant.now().toString())

            log.warn(
                "Access denial summary: user='{}' had {} denials in the last {} minutes",
                username,
                denialCount,
                timeWindowMinutes
            )
        } catch (e: Exception) {
            log.error("Failed to log access denial summary: {}", e.message, e)
        } finally {
            MDC.clear()
        }
    }
}
```

---

## Log Format Specification

### Log Level

**Level**: WARN

**Rationale**:
- NOT ERROR: Access denials are expected behavior for unauthorized users (system is working correctly)
- NOT INFO: Security-relevant events should be highlighted for monitoring
- WARN: Appropriate for security events that require attention but don't indicate system failure

### Logger Name

**Name**: `ACCESS_DENIAL_AUDIT`

**Configuration** (logback.xml):
```xml
<configuration>
    <!-- Dedicated appender for access denial audit logs -->
    <appender name="ACCESS_DENIAL" class="ch.qos.logback.core.rolling.RollingFileAppender">
        <file>logs/access-denial.log</file>
        <rollingPolicy class="ch.qos.logback.core.rolling.TimeBasedRollingPolicy">
            <fileNamePattern>logs/access-denial.%d{yyyy-MM-dd}.log.gz</fileNamePattern>
            <maxHistory>90</maxHistory> <!-- Keep 90 days for security audit -->
            <totalSizeCap>10GB</totalSizeCap>
        </rollingPolicy>
        <encoder>
            <pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} %-5level [%thread] %logger{36} - %msg %X{user_id} %X{user_roles} %X{resource} %X{required_roles} %X{ip_address} %X{timestamp}%n</pattern>
        </encoder>
    </appender>

    <!-- Route ACCESS_DENIAL_AUDIT logger to dedicated appender -->
    <logger name="ACCESS_DENIAL_AUDIT" level="WARN" additivity="false">
        <appender-ref ref="ACCESS_DENIAL" />
        <appender-ref ref="STDOUT" /> <!-- Also log to console for monitoring -->
    </logger>
</configuration>
```

### Message Format

**Pattern**:
```
Access denied: user='<username>', roles=[<user-roles>], resource='<path>', required=[<required-roles>], ip='<ip>', agent='<user-agent>'
```

**Example**:
```
Access denied: user='john.doe@example.com', roles=[USER,REQ], resource='/api/risk-assessments', required=[ADMIN,RISK,SECCHAMPION], ip='192.168.1.100', agent='Mozilla/5.0'
```

### MDC Context Fields

| Field | Type | Required | Description | Example |
|-------|------|----------|-------------|---------|
| `event_type` | String | ✅ | Always "access_denied" for filtering | `access_denied` |
| `user_id` | String | ✅ | Username or email from authentication | `john.doe@example.com` |
| `user_roles` | String | ✅ | Comma-separated roles user has | `USER,REQ` |
| `resource` | String | ✅ | Resource path attempted | `/api/risk-assessments` |
| `required_roles` | String | ✅ | Comma-separated roles required | `ADMIN,RISK,SECCHAMPION` |
| `timestamp` | ISO-8601 | ✅ | Event timestamp in UTC | `2025-10-18T14:23:45.123Z` |
| `ip_address` | String | ❌ | Client IP address | `192.168.1.100` |
| `http_method` | String | ❌ | HTTP method (GET, POST, etc.) | `GET` |
| `user_agent` | String (max 100 chars) | ❌ | Browser/client user-agent | `Mozilla/5.0 (Windows NT 10.0...` |

### Full Log Entry Example

**With all MDC fields**:
```
2025-10-18 14:23:45.123 WARN [http-nio-8080-exec-5] ACCESS_DENIAL_AUDIT - Access denied: user='john.doe@example.com', roles=[USER,REQ], resource='/api/risk-assessments', required=[ADMIN,RISK,SECCHAMPION], ip='192.168.1.100', agent='Mozilla/5.0' event_type=access_denied user_id=john.doe@example.com user_roles=USER,REQ resource=/api/risk-assessments required_roles=ADMIN,RISK,SECCHAMPION timestamp=2025-10-18T14:23:45.123Z ip_address=192.168.1.100 http_method=GET user_agent=Mozilla/5.0
```

---

## Integration with Micronaut Security

### Custom HttpServerFilter (Optional)

To automatically log ALL access denials, implement a custom security filter:

**File**: `/Users/flake/sources/misc/secman/src/backendng/src/main/kotlin/com/secman/security/AccessDenialLoggingFilter.kt`

```kotlin
package com.secman.security

import com.secman.service.AccessDenialLogger
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpResponse
import io.micronaut.http.HttpStatus
import io.micronaut.http.MutableHttpResponse
import io.micronaut.http.annotation.Filter
import io.micronaut.http.filter.HttpServerFilter
import io.micronaut.http.filter.ServerFilterChain
import io.micronaut.security.authentication.Authentication
import io.micronaut.security.filters.SecurityFilter
import io.micronaut.security.utils.SecurityService
import org.reactivestreams.Publisher
import reactor.core.publisher.Mono

/**
 * HTTP filter to automatically log access denials
 * Feature: 025-role-based-access-control
 *
 * This filter runs AFTER Micronaut's SecurityFilter and logs 403 responses.
 * It captures the security context to extract user identity and roles.
 *
 * Order: Runs after SecurityFilter (order = SecurityFilter.ORDER + 1)
 */
@Filter("/**")
class AccessDenialLoggingFilter(
    private val accessDenialLogger: AccessDenialLogger,
    private val securityService: SecurityService
) : HttpServerFilter {

    override fun getOrder(): Int {
        // Run after SecurityFilter to catch 403 responses
        return SecurityFilter.ORDER + 1
    }

    override fun doFilter(
        request: HttpRequest<*>,
        chain: ServerFilterChain
    ): Publisher<MutableHttpResponse<*>> {
        return Mono.from(chain.proceed(request))
            .doOnNext { response ->
                // Check if response is 403 Forbidden
                if (response.status == HttpStatus.FORBIDDEN) {
                    // Get authentication from security service
                    securityService.authentication.ifPresent { auth ->
                        // Extract required roles from @Secured annotation (if available)
                        // This is complex - for now, log with generic "UNKNOWN" for required roles
                        // Controller-specific logging will provide actual required roles

                        accessDenialLogger.logAccessDenialWithMethod(
                            authentication = auth,
                            httpMethod = request.method.name,
                            resource = request.uri.path,
                            requiredRoles = listOf("UNKNOWN"), // TODO: Extract from @Secured annotation
                            ipAddress = request.remoteAddress.hostString,
                            userAgent = request.headers.get("User-Agent")
                        )
                    }
                }
            }
    }
}
```

**Limitation**: Extracting required roles from @Secured annotation at runtime is complex. For complete logging with actual required roles, use controller-level logging (see next section).

### Controller-Level Logging (Recommended)

For accurate logging with required roles, inject AccessDenialLogger in controllers:

**Example** (RiskAssessmentController):

```kotlin
@Controller("/api/risk-assessments")
@Secured("ADMIN", "RISK", "SECCHAMPION")
open class RiskAssessmentController(
    // ... existing dependencies ...
    private val accessDenialLogger: AccessDenialLogger
) {
    // No changes needed - Micronaut handles @Secured enforcement
    // AccessDenialLoggingFilter logs all 403 responses automatically
}
```

**Note**: With the HttpServerFilter approach, no controller changes are needed. The filter automatically logs all 403 responses.

---

## Log Aggregation Queries

### Splunk Queries

**All access denials in last 24 hours**:
```splunk
index=secman logger_name=ACCESS_DENIAL_AUDIT event_type=access_denied earliest=-24h
| stats count by user_id, resource
| sort -count
```

**Top denied users (potential brute-force attempts)**:
```splunk
index=secman logger_name=ACCESS_DENIAL_AUDIT event_type=access_denied earliest=-1h
| stats count as denial_count by user_id
| where denial_count > 10
| sort -denial_count
```

**Denied resources by role**:
```splunk
index=secman logger_name=ACCESS_DENIAL_AUDIT event_type=access_denied
| stats count by user_roles, resource
| table user_roles, resource, count
| sort -count
```

**Access denials from specific IP range**:
```splunk
index=secman logger_name=ACCESS_DENIAL_AUDIT event_type=access_denied ip_address=192.168.*
| table timestamp, user_id, resource, ip_address
```

### ELK (Elasticsearch Query DSL)

**All access denials in last 24 hours**:
```json
{
  "query": {
    "bool": {
      "must": [
        { "term": { "logger_name": "ACCESS_DENIAL_AUDIT" }},
        { "term": { "mdc.event_type": "access_denied" }},
        { "range": { "@timestamp": { "gte": "now-24h" }}}
      ]
    }
  },
  "aggs": {
    "by_user": {
      "terms": { "field": "mdc.user_id" }
    },
    "by_resource": {
      "terms": { "field": "mdc.resource" }
    }
  }
}
```

**Top denied users (potential security incidents)**:
```json
{
  "query": {
    "bool": {
      "must": [
        { "term": { "logger_name": "ACCESS_DENIAL_AUDIT" }},
        { "term": { "mdc.event_type": "access_denied" }},
        { "range": { "@timestamp": { "gte": "now-1h" }}}
      ]
    }
  },
  "aggs": {
    "top_users": {
      "terms": {
        "field": "mdc.user_id",
        "size": 20,
        "order": { "_count": "desc" }
      }
    }
  }
}
```

### Datadog Log Query

**All access denials**:
```
service:secman logger.name:ACCESS_DENIAL_AUDIT @mdc.event_type:access_denied
```

**Group by user**:
```
service:secman logger.name:ACCESS_DENIAL_AUDIT @mdc.event_type:access_denied
| group by @mdc.user_id
| count()
```

**Create monitor for excessive denials**:
```
Condition: count > 50 in 5 minutes
Filter: service:secman logger.name:ACCESS_DENIAL_AUDIT @mdc.event_type:access_denied
Group by: @mdc.user_id
```

---

## Alert Rules

### Security Monitoring Alerts

**Alert 1: Excessive Access Denials (Brute-Force Detection)**
- **Condition**: User has >10 access denials in 5 minutes
- **Severity**: HIGH
- **Action**: Alert security team, potentially lock account
- **Query**: Group by user_id, count denials in 5min window

**Alert 2: New User Attempting Admin Resources**
- **Condition**: User created <24h ago attempting admin endpoints
- **Severity**: MEDIUM
- **Action**: Alert security team for review
- **Query**: Filter by resource=/api/admin/*, cross-reference with user creation date

**Alert 3: Off-Hours Access Attempts**
- **Condition**: Access denials between 10 PM - 6 AM (adjust for timezone)
- **Severity**: MEDIUM
- **Action**: Review for unauthorized access attempts
- **Query**: Filter by timestamp hour

**Alert 4: Suspicious IP Pattern**
- **Condition**: Multiple users from same IP with denials
- **Severity**: MEDIUM
- **Action**: Investigate potential compromised network segment
- **Query**: Group by ip_address, count unique user_ids

---

## Test Requirements

### Unit Tests

**File**: `/Users/flake/sources/misc/secman/src/backendng/src/test/kotlin/com/secman/service/AccessDenialLoggerTest.kt`

```kotlin
import io.micronaut.security.authentication.Authentication
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import io.mockk.mockk
import io.mockk.every
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.slf4j.MDC
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender

@MicronautTest
class AccessDenialLoggerTest {

    @Test
    fun `should set MDC context when logging access denial`() {
        val logger = AccessDenialLogger()
        val auth = mockk<Authentication>()
        every { auth.name } returns "test.user"
        every { auth.roles } returns setOf("USER", "REQ")

        logger.logAccessDenial(
            authentication = auth,
            resource = "/api/risk-assessments",
            requiredRoles = listOf("ADMIN", "RISK"),
            ipAddress = "192.168.1.100"
        )

        // MDC should be cleared after logging
        assertNull(MDC.get("user_id"))
        assertNull(MDC.get("resource"))
    }

    @Test
    fun `should log at WARN level`() {
        val testAppender = ListAppender<ILoggingEvent>()
        testAppender.start()

        val logger = LoggerFactory.getLogger("ACCESS_DENIAL_AUDIT") as Logger
        logger.addAppender(testAppender)

        val service = AccessDenialLogger()
        val auth = mockk<Authentication>()
        every { auth.name } returns "test.user"
        every { auth.roles } returns setOf("USER")

        service.logAccessDenial(
            authentication = auth,
            resource = "/api/test",
            requiredRoles = listOf("ADMIN")
        )

        assertEquals(1, testAppender.list.size)
        assertEquals("WARN", testAppender.list[0].level.toString())
        assertTrue(testAppender.list[0].message.contains("Access denied"))
    }

    @Test
    fun `should include all MDC fields in log message`() {
        val testAppender = ListAppender<ILoggingEvent>()
        testAppender.start()

        val logger = LoggerFactory.getLogger("ACCESS_DENIAL_AUDIT") as Logger
        logger.addAppender(testAppender)

        val service = AccessDenialLogger()
        val auth = mockk<Authentication>()
        every { auth.name } returns "test.user"
        every { auth.roles } returns setOf("USER", "REQ")

        service.logAccessDenial(
            authentication = auth,
            resource = "/api/risk-assessments",
            requiredRoles = listOf("ADMIN", "RISK", "SECCHAMPION"),
            ipAddress = "192.168.1.100",
            userAgent = "Mozilla/5.0"
        )

        val logEvent = testAppender.list[0]
        val mdcMap = logEvent.mdcPropertyMap

        assertEquals("access_denied", mdcMap["event_type"])
        assertEquals("test.user", mdcMap["user_id"])
        assertEquals("USER,REQ", mdcMap["user_roles"])
        assertEquals("/api/risk-assessments", mdcMap["resource"])
        assertEquals("ADMIN,RISK,SECCHAMPION", mdcMap["required_roles"])
        assertEquals("192.168.1.100", mdcMap["ip_address"])
        assertNotNull(mdcMap["timestamp"])
    }

    @Test
    fun `should clear MDC even if logging throws exception`() {
        val service = AccessDenialLogger()
        val auth = mockk<Authentication>()
        every { auth.name } throws RuntimeException("Test exception")

        // Should not throw exception
        assertDoesNotThrow {
            service.logAccessDenial(
                authentication = auth,
                resource = "/api/test",
                requiredRoles = listOf("ADMIN")
            )
        }

        // MDC should still be cleared
        assertNull(MDC.get("user_id"))
    }
}
```

### Integration Tests

**File**: `/Users/flake/sources/misc/secman/src/backendng/src/test/kotlin/com/secman/contract/AccessDenialLoggingContractTest.kt`

```kotlin
@MicronautTest
class AccessDenialLoggingContractTest {

    @Inject
    @Client("/")
    lateinit var client: HttpClient

    @Inject
    lateinit var tokenGenerator: JwtTokenGenerator

    @Test
    fun `should log access denial when user lacks required role`() {
        val testAppender = ListAppender<ILoggingEvent>()
        testAppender.start()

        val logger = LoggerFactory.getLogger("ACCESS_DENIAL_AUDIT") as Logger
        logger.addAppender(testAppender)

        // Attempt to access risk endpoint with REQ role (should be denied)
        val token = tokenGenerator.generateToken("requser", listOf("REQ"))

        val exception = assertThrows<HttpClientResponseException> {
            client.toBlocking().exchange(
                HttpRequest.GET<Any>("/api/risk-assessments")
                    .bearerAuth(token),
                String::class.java
            )
        }

        assertEquals(HttpStatus.FORBIDDEN, exception.status)

        // Verify access denial was logged
        assertTrue(testAppender.list.size > 0)
        val logEvent = testAppender.list.first()
        assertEquals("WARN", logEvent.level.toString())
        assertTrue(logEvent.message.contains("Access denied"))

        // Verify MDC context
        val mdcMap = logEvent.mdcPropertyMap
        assertEquals("access_denied", mdcMap["event_type"])
        assertEquals("requser", mdcMap["user_id"])
        assertTrue(mdcMap["resource"]?.contains("risk-assessments") ?: false)
    }

    @Test
    fun `should include IP address in log when available`() {
        // Similar test but verify ip_address MDC field
        // Implementation depends on how to set/mock request IP in test
    }
}
```

---

## Performance Considerations

### MDC Context Overhead

**Impact**: NEGLIGIBLE
- MDC uses ThreadLocal storage (fast)
- String concatenation for role lists is O(n) where n = number of roles (typically <5)
- Logging overhead: ~1-2ms per access denial

**Mitigation**:
- Log only on WARN level (not INFO)
- Use asynchronous appenders in production (logback AsyncAppender)
- MDC.clear() is essential to prevent memory leaks

### Log Volume Estimation

**Assumptions**:
- 1000 requests/minute to protected endpoints
- 5% unauthorized access rate (50 denials/minute)
- Average log entry size: 500 bytes

**Estimated Volume**:
- 50 denials/min × 500 bytes = 25 KB/min
- 25 KB/min × 60 min × 24 hours = 36 MB/day
- 36 MB/day × 90 days retention = 3.24 GB storage

**Retention Policy**: 90 days (logback config maxHistory=90)

---

## Security Considerations

**Sensitive Data in Logs**:
- ✅ DO log: username/email, roles, resource path, IP address
- ❌ DO NOT log: passwords, JWT tokens, session IDs, personal data (beyond username)
- ❌ DO NOT log: Full error stack traces (may contain sensitive data)

**Log Access Control**:
- Restrict access to access-denial.log file (chmod 600, owned by app user)
- Log aggregation systems should have RBAC (only security team can view)
- Encrypt logs in transit (TLS) and at rest (disk encryption)

**Compliance**:
- GDPR: Usernames/emails are personal data - document retention policy
- SOC 2: Access logs required for audit trail
- PCI DSS: Access denials to payment-related resources must be logged

---

## Implementation Checklist

- [ ] Create AccessDenialLogger service
- [ ] Add logback.xml configuration for ACCESS_DENIAL_AUDIT logger
- [ ] Create AccessDenialLoggingFilter (optional)
- [ ] Configure log file rotation (90 days retention)
- [ ] Write unit tests for AccessDenialLogger
- [ ] Write integration tests for logging on 403 responses
- [ ] Set up log aggregation (Splunk/ELK/Datadog)
- [ ] Create monitoring alerts for excessive denials
- [ ] Document log retention policy (GDPR compliance)
- [ ] Test log queries in aggregation system
- [ ] Review with security team

---

## Related Documents

- [../data-model.md](../data-model.md) - AccessDenialLogger entity definition
- [./role-permission-matrix.md](./role-permission-matrix.md) - Endpoints that trigger access denials
- [../spec.md](../spec.md) - FR-014: Access denial logging requirement
- [../quickstart.md](../quickstart.md) - Implementation sequence

**Questions/Issues**: Contact security team for log aggregation setup and alert configuration.
