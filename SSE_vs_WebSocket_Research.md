# Server-Sent Events (SSE) vs WebSocket Research
## Real-Time Badge Count Updates for Micronaut + React

**Date:** 2025-10-20
**Context:** Notification badge showing count of pending exception requests
**Update Frequency:** Every 5 seconds max after state changes
**Communication Pattern:** Unidirectional (server → client only)
**Fallback:** 30-second polling if real-time connection fails
**Tech Stack:** Micronaut 4.4 (Kotlin) + React 19 with Astro 5.14

---

## Executive Summary

**Recommendation: Use Server-Sent Events (SSE)**

For badge count updates with low-frequency, unidirectional communication, SSE is the optimal choice because:

1. **Simplicity**: Built-in browser API (EventSource), standard HTTP/1.1 protocol
2. **Perfect fit for use case**: One-way server→client updates
3. **Automatic reconnection**: Built-in retry mechanism with exponential backoff
4. **Lower overhead**: No protocol upgrade handshake, standard HTTP infrastructure
5. **Easier scaling**: Works with existing HTTP load balancers and proxies
6. **Comparable performance**: For low-frequency updates, SSE and WebSocket have equivalent performance
7. **Simpler implementation**: Less code, fewer edge cases to handle

---

## 1. SSE Implementation

### 1.1 Micronaut Backend (Kotlin)

#### Dependencies (build.gradle.kts)
```kotlin
dependencies {
    implementation("io.micronaut:micronaut-http-server-netty")
    implementation("io.micronaut.reactor:micronaut-reactor") // For Flux/Mono
    // or
    implementation("io.micronaut.rxjava3:micronaut-rxjava3") // For Flowable
}
```

#### SSE Controller Pattern

**Option 1: Using Reactor (Flux)**
```kotlin
package com.secman.controller

import io.micronaut.http.MediaType
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.micronaut.http.annotation.Produces
import io.micronaut.http.sse.Event
import io.micronaut.security.annotation.Secured
import io.micronaut.security.rules.SecurityRule
import reactor.core.publisher.Flux
import java.time.Duration
import jakarta.inject.Inject

@Controller("/api/notifications")
@Secured(SecurityRule.IS_AUTHENTICATED)
class NotificationSSEController(
    @Inject private val exceptionRequestService: ExceptionRequestService
) {

    @Get("/badge-count")
    @Produces(MediaType.TEXT_EVENT_STREAM)
    fun streamBadgeCount(): Flux<Event<BadgeCountDto>> {
        return Flux.interval(Duration.ofSeconds(5))
            .map {
                val count = exceptionRequestService.getPendingCount()
                Event.of(BadgeCountDto(count))
            }
            .doOnCancel {
                // Log when client disconnects
                println("SSE connection closed by client")
            }
    }

    // Alternative: Event-driven approach (recommended)
    @Get("/badge-count-events")
    @Produces(MediaType.TEXT_EVENT_STREAM)
    fun streamBadgeCountEvents(): Flux<Event<BadgeCountDto>> {
        // Initial event
        val initialCount = exceptionRequestService.getPendingCount()
        val initialEvent = Flux.just(Event.of(BadgeCountDto(initialCount)))

        // Subscribe to application events (when exception requests change)
        val updateEvents = exceptionRequestService.getBadgeCountUpdates()
            .map { count -> Event.of(BadgeCountDto(count)) }

        // Merge initial + updates + heartbeat
        val heartbeat = Flux.interval(Duration.ofSeconds(30))
            .map { Event.of(BadgeCountDto(exceptionRequestService.getPendingCount())) }

        return Flux.merge(initialEvent, updateEvents, heartbeat)
    }
}

data class BadgeCountDto(val count: Long)
```

**Option 2: Using RxJava3 (Flowable)**
```kotlin
import io.micronaut.http.MediaType
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.micronaut.http.annotation.Produces
import io.micronaut.http.sse.Event
import io.micronaut.scheduling.TaskExecutors
import io.micronaut.scheduling.annotation.ExecuteOn
import io.reactivex.rxjava3.core.Flowable
import io.reactivex.rxjava3.core.BackpressureStrategy
import java.util.concurrent.TimeUnit

@Controller("/api/notifications")
@Secured(SecurityRule.IS_AUTHENTICATED)
class NotificationSSEController(
    private val exceptionRequestService: ExceptionRequestService
) {

    @Get("/badge-count")
    @Produces(MediaType.TEXT_EVENT_STREAM)
    @ExecuteOn(TaskExecutors.IO)
    fun streamBadgeCount(): Flowable<Event<BadgeCountDto>> {
        return Flowable.interval(5, TimeUnit.SECONDS)
            .map {
                val count = exceptionRequestService.getPendingCount()
                Event.of(BadgeCountDto(count))
            }
            .onBackpressureBuffer()
    }
}
```

#### Service Layer for Event Broadcasting
```kotlin
package com.secman.service

import io.micronaut.context.event.ApplicationEventPublisher
import jakarta.inject.Singleton
import reactor.core.publisher.Flux
import reactor.core.publisher.Sinks
import java.util.concurrent.ConcurrentHashMap

@Singleton
class ExceptionRequestService(
    private val repository: ExceptionRequestRepository,
    private val eventPublisher: ApplicationEventPublisher<BadgeCountChangedEvent>
) {

    // Broadcast to all SSE connections
    private val badgeCountSink: Sinks.Many<Long> = Sinks.many().multicast().onBackpressureBuffer()

    fun getPendingCount(): Long {
        return repository.countByStatus(ExceptionRequestStatus.PENDING)
    }

    fun getBadgeCountUpdates(): Flux<Long> {
        return badgeCountSink.asFlux()
    }

    @Transactional
    fun createExceptionRequest(request: ExceptionRequest): ExceptionRequest {
        val saved = repository.save(request)
        // Broadcast new count
        val newCount = getPendingCount()
        badgeCountSink.tryEmitNext(newCount)
        eventPublisher.publishEvent(BadgeCountChangedEvent(newCount))
        return saved
    }

    @Transactional
    fun approveRequest(id: Long) {
        val request = repository.findById(id).orElseThrow()
        request.status = ExceptionRequestStatus.APPROVED
        repository.update(request)
        // Broadcast new count
        val newCount = getPendingCount()
        badgeCountSink.tryEmitNext(newCount)
        eventPublisher.publishEvent(BadgeCountChangedEvent(newCount))
    }
}

data class BadgeCountChangedEvent(val count: Long)
```

### 1.2 React Frontend (TypeScript)

#### Custom Hook: useSSE
```typescript
// src/frontend/src/hooks/useSSE.ts
import { useState, useEffect, useRef, useCallback } from 'react';

interface UseSSEOptions {
  url: string;
  onMessage: (data: any) => void;
  onError?: (error: Event) => void;
  enabled?: boolean;
  reconnectInterval?: number; // milliseconds
  maxReconnectAttempts?: number;
}

export function useSSE({
  url,
  onMessage,
  onError,
  enabled = true,
  reconnectInterval = 5000,
  maxReconnectAttempts = 10
}: UseSSEOptions) {
  const [connectionStatus, setConnectionStatus] = useState<'connecting' | 'connected' | 'disconnected' | 'error'>('disconnected');
  const [reconnectCount, setReconnectCount] = useState(0);
  const eventSourceRef = useRef<EventSource | null>(null);
  const reconnectTimeoutRef = useRef<NodeJS.Timeout>();

  const connect = useCallback(() => {
    if (!enabled) return;

    setConnectionStatus('connecting');

    // Create EventSource with credentials for JWT authentication
    const eventSource = new EventSource(url, { withCredentials: true });

    eventSource.onopen = () => {
      console.log('SSE connection opened');
      setConnectionStatus('connected');
      setReconnectCount(0); // Reset on successful connection
    };

    eventSource.onmessage = (event) => {
      try {
        const data = JSON.parse(event.data);
        onMessage(data);
      } catch (error) {
        console.error('Failed to parse SSE message:', error);
      }
    };

    eventSource.onerror = (error) => {
      console.error('SSE error:', error);
      setConnectionStatus('error');
      eventSource.close();

      if (onError) {
        onError(error);
      }

      // Attempt reconnection with exponential backoff
      if (reconnectCount < maxReconnectAttempts) {
        const delay = Math.min(reconnectInterval * Math.pow(2, reconnectCount), 30000); // Max 30 seconds
        console.log(`Reconnecting in ${delay}ms (attempt ${reconnectCount + 1}/${maxReconnectAttempts})`);

        reconnectTimeoutRef.current = setTimeout(() => {
          setReconnectCount(prev => prev + 1);
          connect();
        }, delay);
      } else {
        console.error('Max reconnection attempts reached. Falling back to polling.');
        setConnectionStatus('disconnected');
      }
    };

    eventSourceRef.current = eventSource;
  }, [url, enabled, onMessage, onError, reconnectCount, reconnectInterval, maxReconnectAttempts]);

  useEffect(() => {
    if (enabled) {
      connect();
    }

    // Cleanup on unmount or when dependencies change
    return () => {
      if (eventSourceRef.current) {
        eventSourceRef.current.close();
        eventSourceRef.current = null;
      }
      if (reconnectTimeoutRef.current) {
        clearTimeout(reconnectTimeoutRef.current);
      }
    };
  }, [enabled, connect]);

  const disconnect = useCallback(() => {
    if (eventSourceRef.current) {
      eventSourceRef.current.close();
      eventSourceRef.current = null;
      setConnectionStatus('disconnected');
    }
    if (reconnectTimeoutRef.current) {
      clearTimeout(reconnectTimeoutRef.current);
    }
  }, []);

  return {
    connectionStatus,
    reconnectCount,
    disconnect
  };
}
```

#### Badge Component with SSE
```typescript
// src/frontend/src/components/NotificationBadge.tsx
import React, { useState, useEffect } from 'react';
import { useSSE } from '../hooks/useSSE';
import axios from 'axios';

interface BadgeCountData {
  count: number;
}

export const NotificationBadge: React.FC = () => {
  const [badgeCount, setBadgeCount] = useState<number>(0);
  const [usePolling, setUsePolling] = useState(false);
  const [loading, setLoading] = useState(true);

  // Fallback polling function
  const fetchBadgeCount = async () => {
    try {
      const response = await axios.get<BadgeCountData>('/api/exception-requests/pending-count');
      setBadgeCount(response.data.count);
      setLoading(false);
    } catch (error) {
      console.error('Failed to fetch badge count:', error);
    }
  };

  // SSE connection
  const { connectionStatus, reconnectCount } = useSSE({
    url: '/api/notifications/badge-count',
    onMessage: (data: BadgeCountData) => {
      setBadgeCount(data.count);
      setLoading(false);
    },
    onError: (error) => {
      console.error('SSE connection error:', error);
      // Fall back to polling after max reconnect attempts
      if (reconnectCount >= 10) {
        setUsePolling(true);
      }
    },
    enabled: !usePolling,
    reconnectInterval: 5000,
    maxReconnectAttempts: 10
  });

  // Polling fallback (30-second interval)
  useEffect(() => {
    if (usePolling) {
      console.log('Falling back to polling (30-second interval)');
      fetchBadgeCount(); // Initial fetch

      const interval = setInterval(fetchBadgeCount, 30000);
      return () => clearInterval(interval);
    }
  }, [usePolling]);

  // Initial fetch for immediate display
  useEffect(() => {
    fetchBadgeCount();
  }, []);

  return (
    <div className="position-relative">
      <i className="bi bi-bell-fill fs-5"></i>
      {badgeCount > 0 && (
        <span className="position-absolute top-0 start-100 translate-middle badge rounded-pill bg-danger">
          {badgeCount > 99 ? '99+' : badgeCount}
          <span className="visually-hidden">unread notifications</span>
        </span>
      )}
      {/* Debug info (remove in production) */}
      {process.env.NODE_ENV === 'development' && (
        <small className="text-muted d-block">
          {usePolling ? 'Polling' : connectionStatus}
          {reconnectCount > 0 && ` (retry ${reconnectCount})`}
        </small>
      )}
    </div>
  );
};
```

#### Alternative: Simple Hook without Custom Hook
```typescript
// src/frontend/src/components/NotificationBadge.tsx (simplified)
import React, { useState, useEffect } from 'react';

export const NotificationBadge: React.FC = () => {
  const [badgeCount, setBadgeCount] = useState<number>(0);

  useEffect(() => {
    const eventSource = new EventSource('/api/notifications/badge-count', {
      withCredentials: true
    });

    eventSource.onmessage = (event) => {
      const data = JSON.parse(event.data);
      setBadgeCount(data.count);
    };

    eventSource.onerror = (error) => {
      console.error('SSE error:', error);
      eventSource.close();
    };

    // Cleanup on unmount
    return () => {
      eventSource.close();
    };
  }, []);

  return (
    <div className="position-relative">
      <i className="bi bi-bell-fill fs-5"></i>
      {badgeCount > 0 && (
        <span className="position-absolute top-0 start-100 translate-middle badge rounded-pill bg-danger">
          {badgeCount > 99 ? '99+' : badgeCount}
        </span>
      )}
    </div>
  );
};
```

---

## 2. WebSocket Implementation

### 2.1 Micronaut Backend (Kotlin)

#### Dependencies
```kotlin
dependencies {
    implementation("io.micronaut:micronaut-http-server-netty")
    implementation("io.micronaut:micronaut-websocket")
}
```

#### WebSocket Server
```kotlin
package com.secman.websocket

import io.micronaut.websocket.WebSocketBroadcaster
import io.micronaut.websocket.WebSocketSession
import io.micronaut.websocket.annotation.OnClose
import io.micronaut.websocket.annotation.OnMessage
import io.micronaut.websocket.annotation.OnOpen
import io.micronaut.websocket.annotation.ServerWebSocket
import io.micronaut.security.annotation.Secured
import io.micronaut.security.rules.SecurityRule
import jakarta.inject.Inject
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

@ServerWebSocket("/ws/notifications")
@Secured(SecurityRule.IS_AUTHENTICATED)
class NotificationWebSocket(
    @Inject private val broadcaster: WebSocketBroadcaster,
    @Inject private val exceptionRequestService: ExceptionRequestService
) {

    private val logger = LoggerFactory.getLogger(NotificationWebSocket::class.java)
    private val sessions = ConcurrentHashMap<String, WebSocketSession>()

    @OnOpen
    fun onOpen(session: WebSocketSession) {
        val sessionId = session.id
        sessions[sessionId] = session
        logger.info("WebSocket connection opened: $sessionId")

        // Send initial badge count
        val count = exceptionRequestService.getPendingCount()
        session.sendSync("""{"count": $count}""")
    }

    @OnMessage
    fun onMessage(message: String, session: WebSocketSession): String {
        logger.debug("Received message from ${session.id}: $message")

        // Handle ping/pong for heartbeat
        if (message == "ping") {
            return "pong"
        }

        // Handle refresh request
        if (message == "refresh") {
            val count = exceptionRequestService.getPendingCount()
            return """{"count": $count}"""
        }

        return """{"error": "Unknown command"}"""
    }

    @OnClose
    fun onClose(session: WebSocketSession) {
        val sessionId = session.id
        sessions.remove(sessionId)
        logger.info("WebSocket connection closed: $sessionId")
    }

    // Called by service layer when badge count changes
    fun broadcastBadgeCount(count: Long) {
        val message = """{"count": $count}"""
        broadcaster.broadcastSync(message) { session ->
            sessions.containsKey(session.id)
        }
    }
}
```

#### Service Layer Integration
```kotlin
@Singleton
class ExceptionRequestService(
    private val repository: ExceptionRequestRepository,
    @Inject private val notificationWebSocket: NotificationWebSocket
) {

    fun getPendingCount(): Long {
        return repository.countByStatus(ExceptionRequestStatus.PENDING)
    }

    @Transactional
    fun createExceptionRequest(request: ExceptionRequest): ExceptionRequest {
        val saved = repository.save(request)
        // Broadcast to all WebSocket clients
        val newCount = getPendingCount()
        notificationWebSocket.broadcastBadgeCount(newCount)
        return saved
    }

    @Transactional
    fun approveRequest(id: Long) {
        val request = repository.findById(id).orElseThrow()
        request.status = ExceptionRequestStatus.APPROVED
        repository.update(request)
        // Broadcast to all WebSocket clients
        val newCount = getPendingCount()
        notificationWebSocket.broadcastBadgeCount(newCount)
    }
}
```

### 2.2 React Frontend (TypeScript)

#### Custom Hook: useWebSocket
```typescript
// src/frontend/src/hooks/useWebSocket.ts
import { useState, useEffect, useRef, useCallback } from 'react';

interface UseWebSocketOptions {
  url: string;
  onMessage: (data: any) => void;
  onError?: (error: Event) => void;
  enabled?: boolean;
  reconnectInterval?: number;
  maxReconnectAttempts?: number;
  heartbeatInterval?: number;
}

export function useWebSocket({
  url,
  onMessage,
  onError,
  enabled = true,
  reconnectInterval = 5000,
  maxReconnectAttempts = 10,
  heartbeatInterval = 30000
}: UseWebSocketOptions) {
  const [connectionStatus, setConnectionStatus] = useState<'connecting' | 'connected' | 'disconnected' | 'error'>('disconnected');
  const [reconnectCount, setReconnectCount] = useState(0);
  const wsRef = useRef<WebSocket | null>(null);
  const reconnectTimeoutRef = useRef<NodeJS.Timeout>();
  const heartbeatIntervalRef = useRef<NodeJS.Timeout>();

  const connect = useCallback(() => {
    if (!enabled) return;

    setConnectionStatus('connecting');

    // Determine protocol (ws:// or wss://)
    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    const wsUrl = `${protocol}//${window.location.host}${url}`;

    const ws = new WebSocket(wsUrl);

    ws.onopen = () => {
      console.log('WebSocket connection opened');
      setConnectionStatus('connected');
      setReconnectCount(0);

      // Start heartbeat
      heartbeatIntervalRef.current = setInterval(() => {
        if (ws.readyState === WebSocket.OPEN) {
          ws.send('ping');
        }
      }, heartbeatInterval);
    };

    ws.onmessage = (event) => {
      try {
        // Ignore pong responses
        if (event.data === 'pong') return;

        const data = JSON.parse(event.data);
        onMessage(data);
      } catch (error) {
        console.error('Failed to parse WebSocket message:', error);
      }
    };

    ws.onerror = (error) => {
      console.error('WebSocket error:', error);
      setConnectionStatus('error');

      if (onError) {
        onError(error);
      }
    };

    ws.onclose = () => {
      console.log('WebSocket connection closed');
      setConnectionStatus('disconnected');

      // Clear heartbeat
      if (heartbeatIntervalRef.current) {
        clearInterval(heartbeatIntervalRef.current);
      }

      // Attempt reconnection with exponential backoff
      if (reconnectCount < maxReconnectAttempts) {
        const delay = Math.min(reconnectInterval * Math.pow(2, reconnectCount), 30000);
        console.log(`Reconnecting in ${delay}ms (attempt ${reconnectCount + 1}/${maxReconnectAttempts})`);

        reconnectTimeoutRef.current = setTimeout(() => {
          setReconnectCount(prev => prev + 1);
          connect();
        }, delay);
      } else {
        console.error('Max reconnection attempts reached');
      }
    };

    wsRef.current = ws;
  }, [url, enabled, onMessage, onError, reconnectCount, reconnectInterval, maxReconnectAttempts, heartbeatInterval]);

  useEffect(() => {
    if (enabled) {
      connect();
    }

    return () => {
      if (wsRef.current) {
        wsRef.current.close();
        wsRef.current = null;
      }
      if (reconnectTimeoutRef.current) {
        clearTimeout(reconnectTimeoutRef.current);
      }
      if (heartbeatIntervalRef.current) {
        clearInterval(heartbeatIntervalRef.current);
      }
    };
  }, [enabled, connect]);

  const send = useCallback((message: string) => {
    if (wsRef.current && wsRef.current.readyState === WebSocket.OPEN) {
      wsRef.current.send(message);
    }
  }, []);

  const disconnect = useCallback(() => {
    if (wsRef.current) {
      wsRef.current.close();
      wsRef.current = null;
      setConnectionStatus('disconnected');
    }
    if (reconnectTimeoutRef.current) {
      clearTimeout(reconnectTimeoutRef.current);
    }
    if (heartbeatIntervalRef.current) {
      clearInterval(heartbeatIntervalRef.current);
    }
  }, []);

  return {
    connectionStatus,
    reconnectCount,
    send,
    disconnect
  };
}
```

#### Badge Component with WebSocket
```typescript
// src/frontend/src/components/NotificationBadge.tsx
import React, { useState, useEffect } from 'react';
import { useWebSocket } from '../hooks/useWebSocket';
import axios from 'axios';

interface BadgeCountData {
  count: number;
}

export const NotificationBadge: React.FC = () => {
  const [badgeCount, setBadgeCount] = useState<number>(0);
  const [usePolling, setUsePolling] = useState(false);

  // Fallback polling
  const fetchBadgeCount = async () => {
    try {
      const response = await axios.get<BadgeCountData>('/api/exception-requests/pending-count');
      setBadgeCount(response.data.count);
    } catch (error) {
      console.error('Failed to fetch badge count:', error);
    }
  };

  // WebSocket connection
  const { connectionStatus, reconnectCount } = useWebSocket({
    url: '/ws/notifications',
    onMessage: (data: BadgeCountData) => {
      setBadgeCount(data.count);
    },
    onError: (error) => {
      console.error('WebSocket error:', error);
      if (reconnectCount >= 10) {
        setUsePolling(true);
      }
    },
    enabled: !usePolling,
    reconnectInterval: 5000,
    maxReconnectAttempts: 10,
    heartbeatInterval: 30000
  });

  // Polling fallback
  useEffect(() => {
    if (usePolling) {
      console.log('Falling back to polling');
      fetchBadgeCount();
      const interval = setInterval(fetchBadgeCount, 30000);
      return () => clearInterval(interval);
    }
  }, [usePolling]);

  // Initial fetch
  useEffect(() => {
    fetchBadgeCount();
  }, []);

  return (
    <div className="position-relative">
      <i className="bi bi-bell-fill fs-5"></i>
      {badgeCount > 0 && (
        <span className="position-absolute top-0 start-100 translate-middle badge rounded-pill bg-danger">
          {badgeCount > 99 ? '99+' : badgeCount}
        </span>
      )}
    </div>
  );
};
```

---

## 3. Comparison Table

| Feature | SSE (Server-Sent Events) | WebSocket |
|---------|-------------------------|-----------|
| **Communication Pattern** | Unidirectional (server → client) | Bidirectional (server ↔ client) |
| **Protocol** | HTTP/1.1 | Custom protocol (upgrade from HTTP) |
| **Browser Support** | All modern browsers (IE11 with polyfill) | All modern browsers |
| **Built-in API** | EventSource (native) | WebSocket (native) |
| **Automatic Reconnection** | Yes (built-in with exponential backoff) | No (manual implementation required) |
| **Event ID Tracking** | Yes (Last-Event-ID header for deduplication) | No (manual implementation required) |
| **Infrastructure Compatibility** | Works with HTTP proxies/load balancers | May require WebSocket-aware proxies |
| **Connection Overhead** | Lower (standard HTTP) | Higher (protocol upgrade handshake) |
| **Message Format** | Text (typically JSON) | Text or Binary |
| **Compression** | HTTP compression (gzip, brotli) | Requires manual implementation |
| **Max Connections (HTTP/1.1)** | 6 per domain (browser limit) | No limit (separate protocol) |
| **HTTP/2 Support** | Yes (unlimited connections) | Yes |
| **Scalability** | Easier (standard HTTP infrastructure) | Requires WebSocket-specific infrastructure |
| **Server Complexity** | Low | Medium |
| **Client Complexity** | Very Low | Medium |
| **Latency** | Slightly higher (HTTP overhead) | Slightly lower (persistent connection) |
| **Performance (low frequency)** | Equivalent to WebSocket | Equivalent to SSE |
| **Use Case Fit** | Perfect for badge updates | Overkill for one-way updates |
| **Code Lines (Backend)** | ~30-50 lines | ~60-100 lines |
| **Code Lines (Frontend)** | ~20-40 lines | ~60-120 lines |
| **Authentication** | Standard HTTP headers (JWT in Authorization) | Custom handshake or query params |
| **CORS Handling** | Standard HTTP CORS | WebSocket-specific CORS |
| **Heartbeat/Keep-Alive** | Server sends comment lines (`: heartbeat`) | Manual ping/pong implementation |
| **Error Handling** | Browser auto-retries with onerror event | Manual reconnection logic required |
| **Debugging** | Standard HTTP tools (Network tab, cURL) | WebSocket-specific tools required |
| **Security** | Standard HTTPS/TLS | WSS (WebSocket Secure) |

---

## 4. Recommendation

### **Use Server-Sent Events (SSE)** for the following reasons:

#### 4.1 Perfect Use Case Alignment
- **Unidirectional communication**: Badge counts only flow server → client
- **Low frequency**: Updates every 5 seconds max (not real-time chat)
- **Small payload**: Single number (badge count)
- **No client-to-server messaging needed**: Clients don't need to send data back

#### 4.2 Simplicity & Maintainability
- **Native browser API**: EventSource is built-in, no library needed
- **Less code**: 50% less code than WebSocket implementation
- **Automatic reconnection**: Built-in exponential backoff retry logic
- **Event deduplication**: Last-Event-ID header prevents duplicate updates
- **Easier debugging**: Standard HTTP tools work out of the box

#### 4.3 Infrastructure Benefits
- **HTTP compatibility**: Works with existing load balancers, proxies, CDNs
- **Standard authentication**: JWT in Authorization header (same as REST APIs)
- **HTTP/2 multiplexing**: Unlimited connections on HTTP/2
- **Compression**: Automatic gzip/brotli compression via HTTP

#### 4.4 Performance Equivalence
- For low-frequency updates (5-second intervals), SSE and WebSocket have **equivalent performance**
- SSE overhead: ~200 bytes per message (HTTP headers)
- WebSocket overhead: ~2-6 bytes per frame
- At 5-second intervals, the difference is **negligible** (~0.04 KB/s vs ~0.0012 KB/s)

#### 4.5 Lower Operational Complexity
- No special proxy configuration needed
- Standard HTTP monitoring tools work
- Fewer edge cases to handle
- Better compatibility with corporate firewalls/proxies

---

## 5. Fallback Strategy (30-Second Polling)

### 5.1 When to Fall Back
Trigger polling when:
1. EventSource connection fails 10+ times
2. Browser doesn't support EventSource (check `typeof EventSource !== 'undefined'`)
3. Network conditions prevent persistent connections (rare)

### 5.2 Polling Implementation

#### Backend Endpoint
```kotlin
@Controller("/api/exception-requests")
@Secured(SecurityRule.IS_AUTHENTICATED)
class ExceptionRequestController(
    private val exceptionRequestService: ExceptionRequestService
) {

    @Get("/pending-count")
    fun getPendingCount(): BadgeCountDto {
        val count = exceptionRequestService.getPendingCount()
        return BadgeCountDto(count)
    }
}
```

#### Frontend Service
```typescript
// src/frontend/src/services/badgeService.ts
import axios from 'axios';

export interface BadgeCountDto {
  count: number;
}

export const badgeService = {
  async getPendingCount(): Promise<number> {
    const response = await axios.get<BadgeCountDto>('/api/exception-requests/pending-count');
    return response.data.count;
  }
};
```

#### Polling Hook
```typescript
// src/frontend/src/hooks/usePolling.ts
import { useState, useEffect, useRef } from 'react';

interface UsePollingOptions<T> {
  fetchFn: () => Promise<T>;
  interval: number; // milliseconds
  enabled?: boolean;
}

export function usePolling<T>({
  fetchFn,
  interval,
  enabled = true
}: UsePollingOptions<T>) {
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<Error | null>(null);
  const [loading, setLoading] = useState(true);
  const intervalRef = useRef<NodeJS.Timeout>();

  useEffect(() => {
    if (!enabled) return;

    const poll = async () => {
      try {
        const result = await fetchFn();
        setData(result);
        setError(null);
        setLoading(false);
      } catch (err) {
        setError(err as Error);
        setLoading(false);
      }
    };

    // Initial fetch
    poll();

    // Set up interval
    intervalRef.current = setInterval(poll, interval);

    // Cleanup
    return () => {
      if (intervalRef.current) {
        clearInterval(intervalRef.current);
      }
    };
  }, [fetchFn, interval, enabled]);

  return { data, error, loading };
}
```

#### Badge Component with Polling Fallback
```typescript
// src/frontend/src/components/NotificationBadge.tsx (complete with fallback)
import React, { useState, useEffect } from 'react';
import { useSSE } from '../hooks/useSSE';
import { usePolling } from '../hooks/usePolling';
import { badgeService } from '../services/badgeService';

export const NotificationBadge: React.FC = () => {
  const [badgeCount, setBadgeCount] = useState<number>(0);
  const [usePolling, setUsePolling] = useState(false);
  const [sseSupported, setSseSupported] = useState(true);

  // Check SSE support
  useEffect(() => {
    if (typeof EventSource === 'undefined') {
      console.warn('EventSource not supported, falling back to polling');
      setUsePolling(true);
      setSseSupported(false);
    }
  }, []);

  // SSE connection
  const { connectionStatus, reconnectCount } = useSSE({
    url: '/api/notifications/badge-count',
    onMessage: (data) => {
      setBadgeCount(data.count);
    },
    onError: (error) => {
      console.error('SSE error:', error);
      // Fall back to polling after max reconnect attempts
      if (reconnectCount >= 10) {
        console.warn('Max SSE reconnection attempts reached, falling back to polling');
        setUsePolling(true);
      }
    },
    enabled: sseSupported && !usePolling,
    reconnectInterval: 5000,
    maxReconnectAttempts: 10
  });

  // Polling fallback (30-second interval)
  const { data: polledCount } = usePolling({
    fetchFn: badgeService.getPendingCount,
    interval: 30000,
    enabled: usePolling
  });

  // Update badge count from polling
  useEffect(() => {
    if (usePolling && polledCount !== null) {
      setBadgeCount(polledCount);
    }
  }, [usePolling, polledCount]);

  return (
    <div className="position-relative">
      <i className="bi bi-bell-fill fs-5"></i>
      {badgeCount > 0 && (
        <span className="position-absolute top-0 start-100 translate-middle badge rounded-pill bg-danger">
          {badgeCount > 99 ? '99+' : badgeCount}
          <span className="visually-hidden">{badgeCount} unread notifications</span>
        </span>
      )}
      {/* Debug info (remove in production) */}
      {process.env.NODE_ENV === 'development' && (
        <small className="text-muted d-block mt-1">
          {usePolling ? (
            'Mode: Polling (30s)'
          ) : (
            <>
              Mode: SSE ({connectionStatus})
              {reconnectCount > 0 && ` - Retry ${reconnectCount}`}
            </>
          )}
        </small>
      )}
    </div>
  );
};
```

---

## 6. Connection Management Best Practices

### 6.1 SSE Best Practices

#### Server-Side
1. **Heartbeat**: Send comment line every 30 seconds to keep connection alive
   ```kotlin
   // In SSE controller
   val heartbeat = Flux.interval(Duration.ofSeconds(30))
       .map { Event.of<String>(null).comment("heartbeat") }
   ```

2. **Event IDs**: Include event IDs for client reconnection tracking
   ```kotlin
   Event.of(BadgeCountDto(count))
       .id(System.currentTimeMillis().toString())
   ```

3. **Connection limits**: Monitor active connections, set max per user
   ```kotlin
   // Track connections per user
   private val userConnections = ConcurrentHashMap<String, AtomicInteger>()
   ```

4. **Graceful shutdown**: Close connections cleanly on server shutdown
   ```kotlin
   @PreDestroy
   fun cleanup() {
       badgeCountSink.tryEmitComplete()
   }
   ```

#### Client-Side
1. **Immediate reconnection on error**: EventSource handles this automatically
2. **Heartbeat timeout**: Detect stale connections after 60 seconds of no messages
   ```typescript
   const heartbeatTimeout = useRef<NodeJS.Timeout>();

   const resetHeartbeat = () => {
     if (heartbeatTimeout.current) {
       clearTimeout(heartbeatTimeout.current);
     }
     heartbeatTimeout.current = setTimeout(() => {
       console.warn('No SSE messages for 60s, connection may be stale');
       eventSource.close();
       connect(); // Reconnect
     }, 60000);
   };
   ```

3. **Visible tab optimization**: Pause SSE when tab is hidden
   ```typescript
   useEffect(() => {
     const handleVisibilityChange = () => {
       if (document.hidden) {
         disconnect();
       } else {
         connect();
       }
     };

     document.addEventListener('visibilitychange', handleVisibilityChange);
     return () => document.removeEventListener('visibilitychange', handleVisibilityChange);
   }, []);
   ```

### 6.2 Exponential Backoff Algorithm

```typescript
function getReconnectDelay(attemptNumber: number): number {
  const baseDelay = 1000; // 1 second
  const maxDelay = 30000; // 30 seconds
  const delay = Math.min(baseDelay * Math.pow(2, attemptNumber), maxDelay);

  // Add jitter to prevent thundering herd
  const jitter = Math.random() * 1000;
  return delay + jitter;
}

// Usage
const delay = getReconnectDelay(reconnectCount);
// Attempt 0: 1000-2000ms
// Attempt 1: 2000-3000ms
// Attempt 2: 4000-5000ms
// Attempt 3: 8000-9000ms
// Attempt 4: 16000-17000ms
// Attempt 5+: 30000-31000ms (max)
```

---

## 7. Testing Strategy

### 7.1 Backend Tests

#### SSE Controller Test
```kotlin
@MicronautTest
class NotificationSSEControllerTest {

    @Inject
    lateinit var client: HttpClient

    @MockBean(ExceptionRequestService::class)
    fun exceptionRequestService(): ExceptionRequestService = mockk()

    @Test
    fun `should stream badge count updates via SSE`() {
        // Given
        val service = exceptionRequestService()
        every { service.getPendingCount() } returns 5L

        val events = Flux.interval(Duration.ofSeconds(5))
            .map { Event.of(BadgeCountDto(5L)) }
            .take(3)

        every { service.getBadgeCountUpdates() } returns events

        // When
        val request = HttpRequest.GET<Any>("/api/notifications/badge-count-events")
        val events = client.retrieve(request, Argument.of(Event::class.java, BadgeCountDto::class.java))
            .take(3)
            .collectList()
            .block()

        // Then
        assertNotNull(events)
        assertEquals(3, events.size)
        events.forEach { event ->
            assertEquals(5L, event.data.count)
        }
    }
}
```

### 7.2 Frontend Tests (Playwright E2E)

```typescript
// tests/e2e/notification-badge.spec.ts
import { test, expect } from '@playwright/test';

test.describe('Notification Badge SSE', () => {
  test('should display badge count from SSE', async ({ page }) => {
    // Mock SSE endpoint
    await page.route('/api/notifications/badge-count', async (route) => {
      await route.fulfill({
        status: 200,
        headers: { 'Content-Type': 'text/event-stream' },
        body: 'data: {"count": 5}\n\n',
      });
    });

    await page.goto('/');

    // Wait for badge to appear
    const badge = page.locator('.badge');
    await expect(badge).toBeVisible();
    await expect(badge).toHaveText('5');
  });

  test('should fall back to polling on SSE failure', async ({ page }) => {
    // Mock SSE endpoint to fail
    await page.route('/api/notifications/badge-count', async (route) => {
      await route.abort('failed');
    });

    // Mock polling endpoint
    await page.route('/api/exception-requests/pending-count', async (route) => {
      await route.fulfill({
        status: 200,
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ count: 3 }),
      });
    });

    await page.goto('/');

    // Wait for fallback to polling
    await page.waitForTimeout(5000); // Wait for max reconnect attempts

    const badge = page.locator('.badge');
    await expect(badge).toBeVisible();
    await expect(badge).toHaveText('3');
  });
});
```

---

## 8. Implementation Checklist

### Backend (Micronaut + Kotlin)
- [ ] Add Reactor dependency to build.gradle.kts
- [ ] Create `BadgeCountDto` data class
- [ ] Create `NotificationSSEController` with `/api/notifications/badge-count` endpoint
- [ ] Implement `ExceptionRequestService.getBadgeCountUpdates()` using Sinks
- [ ] Add event broadcasting in service methods (create, approve, reject)
- [ ] Create REST endpoint `/api/exception-requests/pending-count` for polling fallback
- [ ] Write contract tests for SSE endpoint
- [ ] Write unit tests for service layer

### Frontend (React + TypeScript)
- [ ] Create `useSSE` custom hook with reconnection logic
- [ ] Create `usePolling` custom hook for fallback
- [ ] Create `badgeService.ts` with `getPendingCount()` method
- [ ] Update `NotificationBadge.tsx` component with SSE + polling fallback
- [ ] Add debug info (conditional on NODE_ENV)
- [ ] Write Playwright E2E tests for SSE connection
- [ ] Write Playwright E2E tests for polling fallback
- [ ] Test browser compatibility (Chrome, Firefox, Safari, Edge)

### Infrastructure
- [ ] Verify HTTP/1.1 and HTTP/2 support in production
- [ ] Configure nginx/load balancer for SSE (disable buffering)
- [ ] Set up monitoring for SSE connection counts
- [ ] Document SSE endpoint in API docs
- [ ] Add logging for SSE connections/disconnections

---

## 9. Production Considerations

### 9.1 Nginx Configuration (if used)
```nginx
location /api/notifications/ {
    proxy_pass http://backend;
    proxy_http_version 1.1;
    proxy_set_header Connection "";
    proxy_buffering off;  # Critical for SSE
    proxy_cache off;      # Disable caching for SSE
    proxy_read_timeout 86400s;  # 24 hour timeout
}
```

### 9.2 Resource Limits
- **Max SSE connections per user**: 5 (prevent abuse)
- **Connection timeout**: 24 hours (auto-close stale connections)
- **Heartbeat interval**: 30 seconds (keep connection alive)
- **Reconnect max attempts**: 10 (then fall back to polling)

### 9.3 Monitoring & Metrics
Track these metrics:
- Active SSE connections (per user, total)
- SSE connection errors (per error type)
- Fallback-to-polling rate
- Average connection duration
- Badge count update latency

### 9.4 Security
- **Authentication**: Require JWT token (withCredentials: true)
- **Rate limiting**: Max 1 SSE connection per user per session
- **CORS**: Configure CORS headers for SSE endpoints
- **Input validation**: Validate all incoming data in service layer

---

## 10. References

### Documentation
- [MDN: Using Server-Sent Events](https://developer.mozilla.org/en-US/docs/Web/API/Server-sent_events/Using_server-sent_events)
- [Micronaut SSE Guide](https://docs.micronaut.io/latest/guide/#sse)
- [React Hooks Reference](https://react.dev/reference/react)

### Articles
- [SSE vs WebSocket Performance Comparison](https://www.timeplus.com/post/websocket-vs-sse)
- [Ably: WebSockets vs SSE](https://ably.com/blog/websockets-vs-sse)
- [Server-Sent Events: A Practical Guide](https://tigerabrodi.blog/server-sent-events-a-practical-guide-for-the-real-world)

### Example Implementations
- [Micronaut SSE Examples (GitHub)](https://github.com/micronaut-projects/micronaut-kotlin/issues/63)
- [React SSE Patterns (Medium)](https://medium.com/@dima.firsov/easy-server-sent-events-with-react-170894c81bdb)

---

## Appendix A: Quick Start Code Snippets

### Minimal Backend (Kotlin)
```kotlin
@Controller("/api/notifications")
@Secured(SecurityRule.IS_AUTHENTICATED)
class NotificationController(private val service: ExceptionRequestService) {

    @Get("/badge-count")
    @Produces(MediaType.TEXT_EVENT_STREAM)
    fun stream() = Flux.interval(Duration.ofSeconds(5))
        .map { Event.of(BadgeCountDto(service.getPendingCount())) }
}

data class BadgeCountDto(val count: Long)
```

### Minimal Frontend (React)
```typescript
function NotificationBadge() {
  const [count, setCount] = useState(0);

  useEffect(() => {
    const es = new EventSource('/api/notifications/badge-count');
    es.onmessage = (e) => setCount(JSON.parse(e.data).count);
    return () => es.close();
  }, []);

  return count > 0 && <span className="badge">{count}</span>;
}
```

---

**End of Research Document**
