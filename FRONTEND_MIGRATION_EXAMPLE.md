# Frontend Migration Example

This example shows how to update the frontend to use the new dual-backend configuration during migration.

## Before Migration (Java Only)

```typescript
// Old approach - hardcoded to Java backend
const API_BASE = 'http://localhost:9000';

async function login(username: string, password: string) {
  const response = await fetch(`${API_BASE}/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password })
  });
  return response.json();
}

async function getUsers() {
  const response = await fetch(`${API_BASE}/api/users`);
  return response.json();
}

async function getRequirements() {
  const response = await fetch(`${API_BASE}/requirements`);
  return response.json();
}
```

## During Migration (Dual Backend)

```typescript
// New approach - using the API configuration
import { API_ENDPOINTS } from '../utils/api-config';

async function login(username: string, password: string) {
  // Automatically routes to Kotlin backend (port 9001)
  const response = await fetch(API_ENDPOINTS.auth.login(), {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password })
  });
  return response.json();
}

async function getUsers() {
  // Routes to Kotlin backend (migrated)
  const response = await fetch(API_ENDPOINTS.users.list());
  return response.json();
}

async function getRequirements() {
  // Routes to Kotlin backend (migrated)
  const response = await fetch(API_ENDPOINTS.requirements.list());
  return response.json();
}

async function getStandards() {
  // Still routes to Java backend (not yet migrated)
  const response = await fetch(API_ENDPOINTS.standards.list());
  return response.json();
}
```

## Migration Steps

### 1. Update Authentication Components

```typescript
// components/Login.tsx - BEFORE
const handleLogin = async (credentials) => {
  const response = await fetch('http://localhost:9000/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(credentials)
  });
  // ...
};

// components/Login.tsx - AFTER
import { API_ENDPOINTS } from '../utils/api-config';

const handleLogin = async (credentials) => {
  const response = await fetch(API_ENDPOINTS.auth.login(), {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(credentials)
  });
  // ...
};
```

### 2. Update User Management

```typescript
// components/UserManagement.tsx - BEFORE
const fetchUsers = async () => {
  const response = await fetch('http://localhost:9000/api/users');
  setUsers(await response.json());
};

// components/UserManagement.tsx - AFTER
import { API_ENDPOINTS } from '../utils/api-config';

const fetchUsers = async () => {
  const response = await fetch(API_ENDPOINTS.users.list());
  setUsers(await response.json());
};

const createUser = async (userData) => {
  const response = await fetch(API_ENDPOINTS.users.create(), {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(userData)
  });
  return response.json();
};

const updateUser = async (id, userData) => {
  const response = await fetch(API_ENDPOINTS.users.update(id), {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(userData)
  });
  return response.json();
};
```

### 3. Update Requirements Management

```typescript
// components/RequirementManagement.tsx - BEFORE
const exportRequirementsDocx = async () => {
  const response = await fetch('http://localhost:9000/requirements/export/docx');
  const blob = await response.blob();
  // Download logic...
};

// components/RequirementManagement.tsx - AFTER
import { API_ENDPOINTS } from '../utils/api-config';

const exportRequirementsDocx = async () => {
  const response = await fetch(API_ENDPOINTS.requirements.exportDocx());
  const blob = await response.blob();
  // Download logic...
};

const exportRequirementsByUseCase = async (usecaseId) => {
  const response = await fetch(API_ENDPOINTS.requirements.exportDocxByUseCase(usecaseId));
  const blob = await response.blob();
  // Download logic...
};
```

## Configuration Updates

### Enable More APIs as They're Migrated

```typescript
// api-config.ts
export const API_CONFIG = {
  USE_KOTLIN_FOR: {
    auth: true,           // ✅ Migrated
    users: true,          // ✅ Migrated  
    requirements: true,   // ✅ Migrated
    standards: true,      // 🚧 Just migrated - set to true!
    risks: false,         // 🚧 Still Java backend
    // ... others
  }
};
```

### Add New Endpoints

```typescript
// When Standards API is migrated, add:
standards: {
  list: () => getApiEndpoint('api/standards', 'standards'),
  create: () => getApiEndpoint('api/standards', 'standards'),
  get: (id: number) => getApiEndpoint(`api/standards/${id}`, 'standards'),
  update: (id: number) => getApiEndpoint(`api/standards/${id}`, 'standards'),
  delete: (id: number) => getApiEndpoint(`api/standards/${id}`, 'standards'),
},
```

## Testing Both Backends

### Health Checks

```typescript
// Test both backends are running
const checkBackendHealth = async () => {
  try {
    // Java backend
    const javaResponse = await fetch('http://localhost:9000');
    console.log('Java backend:', javaResponse.ok ? '✅ Running' : '❌ Down');
    
    // Kotlin backend  
    const kotlinResponse = await fetch(API_ENDPOINTS.health.check());
    const health = await kotlinResponse.json();
    console.log('Kotlin backend:', health.status === 'UP' ? '✅ Running' : '❌ Down');
  } catch (error) {
    console.error('Backend health check failed:', error);
  }
};
```

### A/B Testing

```typescript
// Compare responses from both backends
const compareBackends = async () => {
  const [javaData, kotlinData] = await Promise.all([
    fetch('http://localhost:9000/requirements').then(r => r.json()),
    fetch('http://localhost:9001/requirements').then(r => r.json())
  ]);
  
  console.log('Java response:', javaData);
  console.log('Kotlin response:', kotlinData);
  console.log('Identical:', JSON.stringify(javaData) === JSON.stringify(kotlinData));
};
```

## Error Handling

```typescript
// Graceful fallback to Java backend if Kotlin fails
const fetchWithFallback = async (kotlinUrl: string, javaUrl: string) => {
  try {
    const response = await fetch(kotlinUrl);
    if (response.ok) return response;
    throw new Error('Kotlin backend failed');
  } catch (error) {
    console.warn('Falling back to Java backend:', error);
    return fetch(javaUrl);
  }
};
```

## Development Workflow

1. **Start both backends**: `./scripts/start-both-backends.sh`
2. **Update API config**: Change feature flags in `api-config.ts`
3. **Update components**: Use `API_ENDPOINTS` instead of hardcoded URLs
4. **Test thoroughly**: Verify same behavior on both backends
5. **Monitor logs**: Check both backend logs during testing
6. **Gradual rollout**: Migrate endpoint by endpoint

## Production Considerations

### Environment Variables

```typescript
// Production configuration
const API_CONFIG = {
  JAVA_BACKEND: process.env.JAVA_BACKEND_URL || 'http://localhost:9000',
  KOTLIN_BACKEND: process.env.KOTLIN_BACKEND_URL || 'http://localhost:9001',
  // ...
};
```

### Load Balancer Configuration

```nginx
# Nginx config for gradual migration
upstream java_backend {
    server localhost:9000;
}

upstream kotlin_backend {
    server localhost:9001;
}

server {
    # Route migrated APIs to Kotlin
    location /login { proxy_pass http://kotlin_backend; }
    location /logout { proxy_pass http://kotlin_backend; }
    location /status { proxy_pass http://kotlin_backend; }
    location /api/users { proxy_pass http://kotlin_backend; }
    location /requirements { proxy_pass http://kotlin_backend; }
    location /health { proxy_pass http://kotlin_backend; }
    
    # Route remaining APIs to Java
    location / { proxy_pass http://java_backend; }
}
```

This approach allows for a safe, gradual migration with instant rollback capability and zero downtime.