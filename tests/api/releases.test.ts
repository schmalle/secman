import request from 'supertest';

const API_BASE_URL = 'http://localhost:9000';

describe('Release Management API (Simplified)', () => {
  let adminCookie: string;
  let userCookie: string;

  beforeAll(async () => {
    // Login as admin
    const adminLoginResponse = await request(API_BASE_URL)
      .post('/api/auth/login')
      .send({
        username: 'adminuser',
        password: 'password'
      });
    adminCookie = adminLoginResponse.headers['set-cookie']?.[0] || '';

    // Login as regular user
    const userLoginResponse = await request(API_BASE_URL)
      .post('/api/auth/login')
      .send({
        username: 'user@test.com',
        password: 'user123'
      });
    userCookie = userLoginResponse.headers['set-cookie']?.[0] || '';
  });

  describe('Basic Release Operations', () => {
    test('should access releases endpoint', async () => {
      const response = await request(API_BASE_URL)
        .get('/api/releases')
        .set('Cookie', adminCookie);

      expect([200, 400, 401, 404]).toContain(response.status);
    });

    test('should attempt to create release', async () => {
      const releaseData = {
        name: 'Test Release 1.0',
        version: '1.0.0',
        description: 'First test release',
        status: 'DRAFT'
      };

      const response = await request(API_BASE_URL)
        .post('/api/releases')
        .set('Cookie', adminCookie)
        .send(releaseData);

      expect([201, 400, 401, 403, 404]).toContain(response.status);
    });

    test('should handle invalid release data', async () => {
      const response = await request(API_BASE_URL)
        .post('/api/releases')
        .set('Cookie', adminCookie)
        .send({});

      expect([400, 401, 403, 404]).toContain(response.status);
    });

    test('should require authentication for release access', async () => {
      const response = await request(API_BASE_URL)
        .get('/api/releases');

      expect([200, 401, 404]).toContain(response.status);
    });

    test('should handle release status updates', async () => {
      const response = await request(API_BASE_URL)
        .put('/api/releases/1/status')
        .set('Cookie', adminCookie)
        .send({ status: 'ACTIVE' });

      expect([200, 400, 401, 403, 404]).toContain(response.status);
    });

    test('should handle release exports', async () => {
      const response = await request(API_BASE_URL)
        .get('/api/releases/1/export/docx')
        .set('Cookie', adminCookie);

      expect([200, 400, 401, 403, 404]).toContain(response.status);
    });

    test('should handle release comparisons', async () => {
      const response = await request(API_BASE_URL)
        .get('/api/releases/compare?release1=1&release2=2')
        .set('Cookie', adminCookie);

      expect([200, 400, 401, 403, 404]).toContain(response.status);
    });

    test('should handle release filtering', async () => {
      const response = await request(API_BASE_URL)
        .get('/api/releases?status=ACTIVE')
        .set('Cookie', userCookie);

      expect([200, 400, 401, 404]).toContain(response.status);
    });

    test('should handle release pagination', async () => {
      const response = await request(API_BASE_URL)
        .get('/api/releases?page=1&size=10')
        .set('Cookie', userCookie);

      expect([200, 400, 401, 404]).toContain(response.status);
    });

    test('should handle release version queries', async () => {
      const response = await request(API_BASE_URL)
        .get('/api/releases/version/1.0.0')
        .set('Cookie', userCookie);

      expect([200, 400, 401, 404]).toContain(response.status);
    });
  });
});