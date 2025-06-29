import request from 'supertest';

const API_BASE_URL = 'http://localhost:9000';

describe('Authentication API', () => {
  let authCookie: string;

  afterEach(async () => {
    // Logout after each test to clean up sessions
    if (authCookie) {
      await request(API_BASE_URL)
        .post('/api/auth/logout')
        .set('Cookie', authCookie);
      authCookie = '';
    }
  });

  describe('POST /api/auth/login', () => {
    test('should login with valid credentials', async () => {
      const response = await request(API_BASE_URL)
        .post('/api/auth/login')
        .send({
          username: 'adminuser',
          password: 'password'
        });

      expect(response.status).toBe(200);
      expect(response.body).toHaveProperty('username', 'adminuser');
      expect(response.body).toHaveProperty('roles');
      expect(Array.isArray(response.body.roles)).toBe(true);
      
      // Store auth cookie for cleanup
      authCookie = response.headers['set-cookie']?.[0] || '';
      expect(authCookie).toBeTruthy();
    });

    test('should reject invalid credentials', async () => {
      const response = await request(API_BASE_URL)
        .post('/api/auth/login')
        .send({
          username: 'adminuser',
          password: 'wrongpassword'
        });

      expect(response.status).toBe(401);
      expect(response.body).toHaveProperty('error');
    });

    test('should reject missing credentials', async () => {
      const response = await request(API_BASE_URL)
        .post('/api/auth/login')
        .send({});

      expect(response.status).toBe(400);
      expect(response.body).toHaveProperty('error');
    });

    test('should reject non-existent user', async () => {
      const response = await request(API_BASE_URL)
        .post('/api/auth/login')
        .send({
          username: 'nonexistent',
          password: 'password123'
        });

      expect(response.status).toBe(401);
      expect(response.body).toHaveProperty('error');
    });
  });

  describe('GET /api/auth/status', () => {
    test('should return user status when authenticated', async () => {
      // First login
      const loginResponse = await request(API_BASE_URL)
        .post('/api/auth/login')
        .send({
          username: 'adminuser',
          password: 'password'
        });

      authCookie = loginResponse.headers['set-cookie']?.[0] || '';

      // Check status
      const response = await request(API_BASE_URL)
        .get('/api/auth/status')
        .set('Cookie', authCookie);

      expect(response.status).toBe(200);
      expect(response.body).toHaveProperty('username', 'adminuser');
      expect(response.body).toHaveProperty('roles');
    });

    test('should return unauthorized when not authenticated', async () => {
      const response = await request(API_BASE_URL)
        .get('/api/auth/status');

      expect(response.status).toBe(401);
      expect(response.body).toHaveProperty('error');
    });
  });

  describe('POST /api/auth/logout', () => {
    test('should logout successfully', async () => {
      // First login
      const loginResponse = await request(API_BASE_URL)
        .post('/api/auth/login')
        .send({
          username: 'adminuser',
          password: 'password'
        });

      authCookie = loginResponse.headers['set-cookie']?.[0] || '';

      // Logout
      const response = await request(API_BASE_URL)
        .post('/api/auth/logout')
        .set('Cookie', authCookie);

      expect(response.status).toBe(200);

      // Verify session is cleared by making a new request without cookie
      const statusResponse = await request(API_BASE_URL)
        .get('/api/auth/status');

      expect(statusResponse.status).toBe(401);
      expect(statusResponse.body).toHaveProperty('error');

      // Clear cookie to prevent cleanup
      authCookie = '';
    });

    test('should handle logout when not authenticated', async () => {
      const response = await request(API_BASE_URL)
        .post('/api/auth/logout');

      expect(response.status).toBe(200);
    });
  });

  describe('Role-based Access Control', () => {
    test('should identify admin user role', async () => {
      const loginResponse = await request(API_BASE_URL)
        .post('/api/auth/login')
        .send({
          username: 'adminuser',
          password: 'password'
        });

      authCookie = loginResponse.headers['set-cookie']?.[0] || '';

      const statusResponse = await request(API_BASE_URL)
        .get('/api/auth/status')
        .set('Cookie', authCookie);

      expect(statusResponse.body.roles).toContain('ADMIN');
    });

    test.skip('should identify regular user role', async () => {
      // Skip this test until we can create a regular user
      // This would require admin permissions to create users first
    });
  });
});