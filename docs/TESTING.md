# Testing Guide for Secman

This guide covers testing strategies and practices for the Secman application, including unit tests, integration tests, and end-to-end testing in both containerized and local environments.

## Testing Architecture

Secman uses a comprehensive testing approach:

- **Backend**: Micronaut Test with JUnit 5, Mockk for Kotlin
- **Frontend**: Playwright for E2E, Vitest for unit tests
- **Integration**: Docker-based testing with real services
- **API Testing**: HTTP client tests with Micronaut

## Backend Testing (Micronaut + Kotlin)

### Framework and Dependencies

The backend uses these testing frameworks:
- **Micronaut Test**: Integration testing framework
- **JUnit 5**: Test runner and assertions
- **Mockk**: Mocking library for Kotlin
- **H2**: In-memory database for tests
- **TestContainers**: Container-based integration tests

### Unit Testing

**Service Layer Tests:**
```kotlin
@MicronautTest
class UserServiceTest {

    @Inject
    lateinit var userService: UserService

    @MockBean(UserRepository::class)
    fun userRepository(): UserRepository = mockk()

    @Test
    fun `should create user successfully`() {
        // Given
        val userData = CreateUserRequest(
            username = "testuser",
            email = "test@example.com",
            password = "password123"
        )
        
        every { userRepository().save(any()) } returns User(...)
        
        // When
        val result = userService.createUser(userData)
        
        // Then
        assertThat(result.username).isEqualTo("testuser")
        verify { userRepository().save(any()) }
    }
}
```

**Repository Tests:**
```kotlin
@MicronautTest
@Transactional
class UserRepositoryTest {

    @Inject
    lateinit var userRepository: UserRepository

    @Test
    fun `should find user by username`() {
        // Given
        val user = User(
            username = "testuser",
            email = "test@example.com",
            passwordHash = "hashed"
        )
        userRepository.save(user)

        // When
        val found = userRepository.findByUsername("testuser")

        // Then
        assertThat(found).isPresent
        assertThat(found.get().email).isEqualTo("test@example.com")
    }
}
```

### Controller/HTTP Testing

```kotlin
@MicronautTest
class UserControllerTest {

    @Inject
    @field:Client("/")
    lateinit var client: HttpClient

    @MockBean(UserService::class)
    fun userService(): UserService = mockk()

    @Test
    fun `should return user by id`() {
        // Given
        val userId = 1L
        val user = User(id = userId, username = "testuser", email = "test@example.com")
        every { userService().findById(userId) } returns user

        // When
        val response = client.toBlocking().exchange(
            HttpRequest.GET<Any>("/api/users/$userId"),
            UserResponse::class.java
        )

        // Then
        assertThat(response.status).isEqualTo(HttpStatus.OK)
        assertThat(response.body().username).isEqualTo("testuser")
    }

    @Test
    fun `should handle user not found`() {
        // Given
        val userId = 999L
        every { userService().findById(userId) } throws UserNotFoundException()

        // When & Then
        val exception = assertThrows<HttpClientResponseException> {
            client.toBlocking().exchange(
                HttpRequest.GET<Any>("/api/users/$userId"),
                UserResponse::class.java
            )
        }
        
        assertThat(exception.status).isEqualTo(HttpStatus.NOT_FOUND)
    }
}
```

### Security Testing

```kotlin
@MicronautTest
class SecurityTest {

    @Inject
    @field:Client("/")
    lateinit var client: HttpClient

    @Test
    fun `should require authentication for protected endpoints`() {
        // When & Then
        val exception = assertThrows<HttpClientResponseException> {
            client.toBlocking().exchange(
                HttpRequest.GET<Any>("/api/users"),
                String::class.java
            )
        }
        
        assertThat(exception.status).isEqualTo(HttpStatus.UNAUTHORIZED)
    }

    @Test
    fun `should allow access with valid JWT token`() {
        // Given
        val token = generateValidJwtToken()

        // When
        val response = client.toBlocking().exchange(
            HttpRequest.GET<Any>("/api/users")
                .bearerAuth(token),
            String::class.java
        )

        // Then
        assertThat(response.status).isEqualTo(HttpStatus.OK)
    }
}
```

### Database Integration Tests

```kotlin
@MicronautTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DatabaseIntegrationTest {

    @Inject
    lateinit var dataSource: DataSource

    @Test
    fun `should connect to database successfully`() {
        dataSource.connection.use { connection ->
            assertThat(connection.isValid(5)).isTrue()
        }
    }

    @Test
    @Sql(scripts = ["/sql/test-data.sql"])
    fun `should load test data correctly`() {
        // Test with pre-loaded data
    }
}
```

### Running Backend Tests

```bash
cd src/backendng

# Run all tests
gradle test

# Run specific test class
gradle test --tests "com.secman.service.UserServiceTest"

# Run tests with specific tag
gradle test --tests "*Integration*"

# Run tests with coverage
gradle test jacocoTestReport

# Run tests in continuous mode
gradle test --continuous

# Run tests with specific profile
gradle test -Dmicronaut.environments=test
```

### Test Configuration

**Test Application Configuration (`application-test.yml`):**
```yaml
datasources:
  default:
    url: jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE
    driverClassName: org.h2.Driver
    username: sa
    password: ''

jpa:
  default:
    properties:
      hibernate:
        hbm2ddl:
          auto: create-drop

micronaut:
  security:
    token:
      jwt:
        signatures:
          secret:
            generator:
              secret: test-secret-key-256-bits-long-for-testing-purposes-only
```

## Frontend Testing (Playwright + Vitest)

### Unit Testing with Vitest

**Component Tests:**
```typescript
import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { LoginForm } from '../components/LoginForm'

describe('LoginForm', () => {
  it('renders login form correctly', () => {
    render(<LoginForm />)
    
    expect(screen.getByLabelText(/username/i)).toBeInTheDocument()
    expect(screen.getByLabelText(/password/i)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /login/i })).toBeInTheDocument()
  })

  it('validates required fields', async () => {
    render(<LoginForm />)
    
    const submitButton = screen.getByRole('button', { name: /login/i })
    fireEvent.click(submitButton)
    
    expect(await screen.findByText(/username is required/i)).toBeInTheDocument()
    expect(await screen.findByText(/password is required/i)).toBeInTheDocument()
  })
})
```

**Utility Function Tests:**
```typescript
import { describe, it, expect } from 'vitest'
import { formatDate, validateEmail } from '../utils/helpers'

describe('Helper Functions', () => {
  describe('formatDate', () => {
    it('formats date correctly', () => {
      const date = new Date('2024-01-15T10:30:00Z')
      expect(formatDate(date)).toBe('2024-01-15')
    })
  })

  describe('validateEmail', () => {
    it('validates correct email format', () => {
      expect(validateEmail('user@example.com')).toBe(true)
      expect(validateEmail('invalid-email')).toBe(false)
    })
  })
})
```

### End-to-End Testing with Playwright

**Page Object Model:**
```typescript
// pages/LoginPage.ts
import { Page, Locator } from '@playwright/test'

export class LoginPage {
  readonly page: Page
  readonly usernameInput: Locator
  readonly passwordInput: Locator
  readonly loginButton: Locator
  readonly errorMessage: Locator

  constructor(page: Page) {
    this.page = page
    this.usernameInput = page.getByLabel('Username')
    this.passwordInput = page.getByLabel('Password')
    this.loginButton = page.getByRole('button', { name: 'Login' })
    this.errorMessage = page.getByTestId('error-message')
  }

  async goto() {
    await this.page.goto('/login')
  }

  async login(username: string, password: string) {
    await this.usernameInput.fill(username)
    await this.passwordInput.fill(password)
    await this.loginButton.click()
  }
}
```

**E2E Test Examples:**
```typescript
import { test, expect } from '@playwright/test'
import { LoginPage } from '../pages/LoginPage'

test.describe('Authentication', () => {
  test('successful login redirects to dashboard', async ({ page }) => {
    const loginPage = new LoginPage(page)
    
    await loginPage.goto()
    await loginPage.login('adminuser', 'password')
    
    await expect(page).toHaveURL('/dashboard')
    await expect(page.getByText('Welcome, adminuser')).toBeVisible()
  })

  test('invalid credentials show error message', async ({ page }) => {
    const loginPage = new LoginPage(page)
    
    await loginPage.goto()
    await loginPage.login('invalid', 'credentials')
    
    await expect(loginPage.errorMessage).toBeVisible()
    await expect(loginPage.errorMessage).toContainText('Invalid credentials')
  })
})

test.describe('Requirements Management', () => {
  test.beforeEach(async ({ page }) => {
    // Login before each test
    const loginPage = new LoginPage(page)
    await loginPage.goto()
    await loginPage.login('adminuser', 'password')
  })

  test('create new requirement', async ({ page }) => {
    await page.goto('/requirements')
    await page.getByRole('button', { name: 'Add Requirement' }).click()
    
    await page.getByLabel('Title').fill('Test Requirement')
    await page.getByLabel('Description').fill('This is a test requirement')
    await page.getByRole('button', { name: 'Save' }).click()
    
    await expect(page.getByText('Test Requirement')).toBeVisible()
  })
})
```

### API Testing

```typescript
import { test, expect } from '@playwright/test'

test.describe('API Tests', () => {
  test('GET /api/users returns user list', async ({ request }) => {
    // Login to get token
    const loginResponse = await request.post('/api/auth/login', {
      data: {
        username: 'adminuser',
        password: 'password'
      }
    })
    
    const { token } = await loginResponse.json()
    
    // Test API endpoint
    const response = await request.get('/api/users', {
      headers: {
        'Authorization': `Bearer ${token}`
      }
    })
    
    expect(response.status()).toBe(200)
    const users = await response.json()
    expect(Array.isArray(users)).toBe(true)
  })

  test('POST /api/requirements creates new requirement', async ({ request }) => {
    // Get auth token
    const token = await getAuthToken(request)
    
    const response = await request.post('/api/requirements', {
      headers: {
        'Authorization': `Bearer ${token}`,
        'Content-Type': 'application/json'
      },
      data: {
        title: 'New Requirement',
        description: 'Test requirement description',
        priority: 'HIGH'
      }
    })
    
    expect(response.status()).toBe(201)
    const requirement = await response.json()
    expect(requirement.title).toBe('New Requirement')
  })
})
```

### Running Frontend Tests

```bash
cd src/frontend

# Run unit tests
npm run test

# Run unit tests in watch mode
npm run test:watch

# Run E2E tests
npm run test:e2e

# Run E2E tests with UI
npm run test:ui

# Run specific test file
npx playwright test auth.spec.ts

# Run tests in specific browser
npx playwright test --project=chromium

# Generate test report
npx playwright show-report
```

## Docker-based Testing

### Test Environment Setup

**Docker Compose for Testing:**
```yaml
# docker-compose.test.yml
version: '3.8'

services:
  database-test:
    image: mariadb:11.4
    environment:
      MYSQL_ROOT_PASSWORD: testroot
      MYSQL_DATABASE: secman_test
      MYSQL_USER: secman_test
      MYSQL_PASSWORD: testpass
    ports:
      - "3307:3306"
    tmpfs:
      - /var/lib/mysql  # Use in-memory storage for speed

  backend-test:
    build:
      context: .
      dockerfile: docker/backend/Dockerfile.dev
    environment:
      - DB_URL=jdbc:mariadb://database-test:3306/secman_test
      - DB_USERNAME=secman_test
      - DB_PASSWORD=testpass
      - MICRONAUT_ENVIRONMENTS=test
    depends_on:
      - database-test
    ports:
      - "8081:8080"

  frontend-test:
    build:
      context: .
      dockerfile: docker/frontend/Dockerfile.dev
    environment:
      - NODE_ENV=test
    ports:
      - "4322:4321"
    depends_on:
      - backend-test
```

**Running Tests in Docker:**
```bash
# Start test environment
docker-compose -f docker-compose.test.yml up -d

# Run backend tests
docker-compose -f docker-compose.test.yml exec backend-test gradle test

# Run frontend tests
docker-compose -f docker-compose.test.yml exec frontend-test npm run test

# Run E2E tests against containerized app
PLAYWRIGHT_BASE_URL=http://localhost:4322 npm run test:e2e

# Cleanup
docker-compose -f docker-compose.test.yml down -v
```

### TestContainers Integration

```kotlin
@MicronautTest
@Testcontainers
class DatabaseIntegrationTest {

    companion object {
        @Container
        @JvmStatic
        val mariadb = MariaDBContainer("mariadb:11.4")
            .withDatabaseName("secman_test")
            .withUsername("test")
            .withPassword("test")
    }

    @Test
    fun `should perform database operations`() {
        // Test with real database container
    }

    @TestFactory
    fun testDatabaseUrl(): DynamicPropertyRegistry.() -> Unit = {
        add("datasources.default.url", mariadb::getJdbcUrl)
        add("datasources.default.username", mariadb::getUsername)
        add("datasources.default.password", mariadb::getPassword)
    }
}
```

## Test Data Management

### Backend Test Data

**Data Builders:**
```kotlin
class UserTestDataBuilder {
    private var username = "testuser"
    private var email = "test@example.com"
    private var role = UserRole.USER
    
    fun withUsername(username: String) = apply { this.username = username }
    fun withEmail(email: String) = apply { this.email = email }
    fun withRole(role: UserRole) = apply { this.role = role }
    
    fun build() = User(
        username = username,
        email = email,
        role = role,
        passwordHash = "\$2a\$10\$..."
    )
}

// Usage
val adminUser = UserTestDataBuilder()
    .withUsername("admin")
    .withRole(UserRole.ADMIN)
    .build()
```

**Test Fixtures:**
```sql
-- src/test/resources/sql/test-data.sql
INSERT INTO users (username, email, password_hash, role, created_at) VALUES
('adminuser', 'admin@test.com', '$2a$10$...', 'ADMIN', NOW()),
('normaluser', 'user@test.com', '$2a$10$...', 'USER', NOW());

INSERT INTO requirements (title, description, priority, status, created_by, created_at) VALUES
('Test Requirement 1', 'Description 1', 'HIGH', 'OPEN', 1, NOW()),
('Test Requirement 2', 'Description 2', 'MEDIUM', 'IN_PROGRESS', 1, NOW());
```

### Frontend Test Data

**Mock API Responses:**
```typescript
// tests/mocks/api.ts
import { http, HttpResponse } from 'msw'

export const handlers = [
  http.get('/api/users', () => {
    return HttpResponse.json([
      { id: 1, username: 'adminuser', email: 'admin@test.com', role: 'ADMIN' },
      { id: 2, username: 'normaluser', email: 'user@test.com', role: 'USER' }
    ])
  }),

  http.post('/api/auth/login', async ({ request }) => {
    const { username, password } = await request.json()
    
    if (username === 'adminuser' && password === 'password') {
      return HttpResponse.json({ token: 'fake-jwt-token' })
    }
    
    return HttpResponse.json(
      { error: 'Invalid credentials' },
      { status: 401 }
    )
  })
]
```

## Continuous Integration

### GitHub Actions Example

```yaml
# .github/workflows/test.yml
name: Test Suite

on: [push, pull_request]

jobs:
  backend-tests:
    runs-on: ubuntu-latest
    
    services:
      mariadb:
        image: mariadb:11.4
        env:
          MYSQL_ROOT_PASSWORD: root
          MYSQL_DATABASE: secman_test
          MYSQL_USER: secman_test
          MYSQL_PASSWORD: test
        ports:
          - 3306:3306
        options: --health-cmd="mysqladmin ping" --health-interval=10s --health-timeout=5s --health-retries=3

    steps:
      - uses: actions/checkout@v4
      
      - name: Set up Java 17
        uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'
          
      - name: Run backend tests
        run: |
          cd src/backendng
          gradle test
        env:
          DB_URL: jdbc:mariadb://localhost:3306/secman_test
          DB_USERNAME: secman_test
          DB_PASSWORD: test

  frontend-tests:
    runs-on: ubuntu-latest
    
    steps:
      - uses: actions/checkout@v4
      
      - name: Set up Node.js
        uses: actions/setup-node@v4
        with:
          node-version: '20'
          cache: 'npm'
          cache-dependency-path: src/frontend/package-lock.json
          
      - name: Install dependencies
        run: |
          cd src/frontend
          npm ci
          
      - name: Run unit tests
        run: |
          cd src/frontend
          npm run test
          
      - name: Install Playwright
        run: |
          cd src/frontend
          npx playwright install
          
      - name: Run E2E tests
        run: |
          cd src/frontend
          npm run test:e2e

  docker-tests:
    runs-on: ubuntu-latest
    
    steps:
      - uses: actions/checkout@v4
      
      - name: Build Docker images
        run: |
          docker-compose -f docker-compose.test.yml build
          
      - name: Run integration tests
        run: |
          docker-compose -f docker-compose.test.yml up -d
          # Wait for services to be ready
          sleep 30
          # Run tests
          docker-compose -f docker-compose.test.yml exec -T backend-test gradle test
          docker-compose -f docker-compose.test.yml exec -T frontend-test npm run test
          
      - name: Cleanup
        run: |
          docker-compose -f docker-compose.test.yml down -v
```

## Performance Testing

### Backend Load Testing

```kotlin
@MicronautTest
class PerformanceTest {

    @Inject
    @field:Client("/")
    lateinit var client: HttpClient

    @Test
    fun `should handle concurrent requests`() {
        val futures = (1..100).map {
            CompletableFuture.supplyAsync {
                client.toBlocking().retrieve("/api/health")
            }
        }
        
        val results = CompletableFuture.allOf(*futures.toTypedArray()).get()
        
        // Assert all requests succeeded
        futures.forEach { future ->
            assertThat(future.get()).contains("UP")
        }
    }
}
```

### Frontend Performance Testing

```typescript
import { test, expect } from '@playwright/test'

test('page load performance', async ({ page }) => {
  const startTime = Date.now()
  
  await page.goto('/')
  await page.waitForLoadState('networkidle')
  
  const loadTime = Date.now() - startTime
  expect(loadTime).toBeLessThan(3000) // Should load within 3 seconds
  
  // Check Core Web Vitals
  const metrics = await page.evaluate(() => {
    return JSON.stringify(performance.getEntriesByType('navigation'))
  })
  
  console.log('Performance metrics:', metrics)
})
```

## Test Reporting and Coverage

### Backend Coverage

```bash
cd src/backendng

# Generate coverage report
gradle test jacocoTestReport

# View coverage report
open build/reports/jacoco/test/html/index.html
```

### Frontend Coverage

```bash
cd src/frontend

# Run tests with coverage
npm run test:coverage

# View coverage report
open coverage/index.html
```

### Combined Reporting

```bash
# Generate comprehensive test report
./scripts/generate-test-report.sh
```

This testing guide provides comprehensive coverage of testing strategies for the Micronaut/Kotlin and Astro/React technology stack, with particular focus on containerized testing environments suitable for modern development workflows.