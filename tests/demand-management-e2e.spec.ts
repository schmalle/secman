import { test, expect } from '@playwright/test';
import { 
  login, 
  navigateTo, 
  waitForApiResponse,
  getAuthToken,
  cleanupTestData
} from '../src/frontend/test-helpers';

test.describe('Demand Management End-to-End Test', () => {
  let authToken: string;
  let createdDemandId: string;
  let createdAssetId: string;
  let createdRiskAssessmentId: string;

  test.beforeAll(async ({ request }) => {
    // Clean up any existing test data
    await cleanupTestData(request);
    
    // Login and get auth token
    const loginResponse = await request.post('/api/auth/login', {
      data: {
        username: process.env.PLAYWRIGHT_TEST_USERNAME || 'adminuser',
        password: process.env.PLAYWRIGHT_TEST_PASSWORD || 'password'
      }
    });
    const loginData = await loginResponse.json();
    authToken = loginData.token;
  });

  test.afterAll(async ({ request }) => {
    // Clean up created test data
    if (createdRiskAssessmentId) {
      await request.delete(`/api/risk-assessments/${createdRiskAssessmentId}`, {
        headers: { 'Authorization': `Bearer ${authToken}` }
      });
    }
    if (createdDemandId) {
      await request.delete(`/api/demands/${createdDemandId}`, {
        headers: { 'Authorization': `Bearer ${authToken}` }
      });
    }
    if (createdAssetId) {
      await request.delete(`/api/assets/${createdAssetId}`, {
        headers: { 'Authorization': `Bearer ${authToken}` }
      });
    }
  });

  test('Complete demand workflow - Create new asset demand', async ({ page, request }) => {
    // Step 1: Login to the application
    await page.goto('http://localhost:4321/login');
    await login(page, 
      process.env.PLAYWRIGHT_TEST_USERNAME || 'adminuser',
      process.env.PLAYWRIGHT_TEST_PASSWORD || 'password'
    );
    
    // Verify successful login
    await expect(page).toHaveURL('http://localhost:4321/');
    
    // Step 2: Navigate to Demand Management
    await page.goto('http://localhost:4321/demands');
    await page.waitForLoadState('networkidle');
    
    // Verify the page loaded
    await expect(page.locator('h1')).toContainText('Demand Management');
    
    // Step 3: Create a new demand for creating a new asset
    await page.click('button:has-text("Create New Demand")');
    
    // Fill in the demand form
    await page.selectOption('#demandType', 'CREATE_NEW');
    await page.fill('#title', 'E2E Test - New Server Infrastructure');
    await page.fill('#description', 'Test demand for automated E2E testing - Creating new server infrastructure');
    await page.selectOption('#priority', 'HIGH');
    await page.fill('#requestor', 'E2E Test Suite');
    await page.fill('#justification', 'Required for expanding test environment capacity');
    
    // Fill in new asset information
    await page.fill('#newAssetName', 'E2E-Test-Server-01');
    await page.fill('#newAssetDescription', 'Test server for E2E automated testing');
    await page.selectOption('#newAssetType', 'SERVER');
    await page.selectOption('#newAssetCriticality', 'MEDIUM');
    await page.fill('#newAssetOwner', 'Test Team');
    await page.fill('#newAssetLocation', 'Test Data Center');
    
    // Submit the form
    const [createResponse] = await Promise.all([
      page.waitForResponse(resp => resp.url().includes('/api/demands') && resp.status() === 201),
      page.click('button:has-text("Submit Demand")')
    ]);
    
    const createdDemand = await createResponse.json();
    createdDemandId = createdDemand.id;
    
    // Verify demand was created
    expect(createdDemand.title).toBe('E2E Test - New Server Infrastructure');
    expect(createdDemand.status).toBe('PENDING');
    expect(createdDemand.type).toBe('CREATE_NEW');
    
    // Step 4: Approve the demand
    await page.click(`button[data-demand-id="${createdDemandId}"][data-action="approve"]`);
    
    // Confirm approval in dialog
    await page.click('button:has-text("Confirm Approval")');
    
    // Wait for approval to complete
    await page.waitForResponse(resp => 
      resp.url().includes(`/api/demands/${createdDemandId}/approve`) && 
      resp.status() === 200
    );
    
    // Verify demand status changed to APPROVED
    await page.reload();
    const approvedStatus = await page.locator(`tr[data-demand-id="${createdDemandId}"] td.status`).textContent();
    expect(approvedStatus).toContain('APPROVED');
    
    // Step 5: Navigate to Risk Assessment and create assessment for the demand
    await page.goto('http://localhost:4321/risk-assessments');
    await page.waitForLoadState('networkidle');
    
    await page.click('button:has-text("Create New Risk Assessment")');
    
    // Select the demand from dropdown
    await page.selectOption('#demandId', createdDemandId.toString());
    
    // Fill in risk assessment details
    await page.fill('#assessmentName', 'E2E Test Risk Assessment');
    await page.fill('#description', 'Risk assessment for E2E test server infrastructure');
    await page.selectOption('#riskLevel', 'MEDIUM');
    await page.fill('#likelihood', '3');
    await page.fill('#impact', '3');
    
    // Add risk mitigation measures
    await page.fill('#mitigationMeasures', 'Implement security hardening, regular patching, monitoring');
    await page.fill('#residualRisk', 'LOW');
    
    // Submit risk assessment
    const [riskResponse] = await Promise.all([
      page.waitForResponse(resp => resp.url().includes('/api/risk-assessments') && resp.status() === 201),
      page.click('button:has-text("Create Risk Assessment")')
    ]);
    
    const createdRiskAssessment = await riskResponse.json();
    createdRiskAssessmentId = createdRiskAssessment.id;
    
    // Verify risk assessment was created
    expect(createdRiskAssessment.name).toBe('E2E Test Risk Assessment');
    expect(createdRiskAssessment.demandId).toBe(createdDemandId);
    
    // Step 6: Verify demand status changed to IN_PROGRESS
    await page.goto('http://localhost:4321/demands');
    await page.waitForLoadState('networkidle');
    
    const progressStatus = await page.locator(`tr[data-demand-id="${createdDemandId}"] td.status`).textContent();
    expect(progressStatus).toContain('IN_PROGRESS');
    
    // Step 7: Complete the demand
    await page.click(`button[data-demand-id="${createdDemandId}"][data-action="complete"]`);
    await page.click('button:has-text("Confirm Completion")');
    
    await page.waitForResponse(resp => 
      resp.url().includes(`/api/demands/${createdDemandId}/complete`) && 
      resp.status() === 200
    );
    
    // Verify final status
    await page.reload();
    const completedStatus = await page.locator(`tr[data-demand-id="${createdDemandId}"] td.status`).textContent();
    expect(completedStatus).toContain('COMPLETED');
  });

  test('Change existing asset demand workflow', async ({ page, request }) => {
    // Step 1: Create a test asset first
    const assetResponse = await request.post('/api/assets', {
      headers: { 'Authorization': `Bearer ${authToken}` },
      data: {
        name: 'E2E-Existing-Asset',
        description: 'Existing asset for change testing',
        type: 'APPLICATION',
        criticality: 'HIGH',
        owner: 'Test Team',
        location: 'Production'
      }
    });
    
    const testAsset = await assetResponse.json();
    createdAssetId = testAsset.id;
    
    // Step 2: Navigate to demands page
    await page.goto('http://localhost:4321/demands');
    await page.waitForLoadState('networkidle');
    
    // Step 3: Create a CHANGE demand
    await page.click('button:has-text("Create New Demand")');
    
    await page.selectOption('#demandType', 'CHANGE');
    await page.fill('#title', 'E2E Test - Modify Existing Asset');
    await page.fill('#description', 'Test demand for modifying existing asset configuration');
    await page.selectOption('#priority', 'MEDIUM');
    await page.fill('#requestor', 'E2E Test Suite');
    await page.fill('#justification', 'Security hardening required');
    
    // Select existing asset
    await page.selectOption('#assetId', testAsset.id.toString());
    
    // Submit
    const [changeResponse] = await Promise.all([
      page.waitForResponse(resp => resp.url().includes('/api/demands') && resp.status() === 201),
      page.click('button:has-text("Submit Demand")')
    ]);
    
    const changeDemand = await changeResponse.json();
    
    // Verify
    expect(changeDemand.type).toBe('CHANGE');
    expect(changeDemand.assetId).toBe(testAsset.id);
    expect(changeDemand.status).toBe('PENDING');
    
    // Clean up this demand
    await request.delete(`/api/demands/${changeDemand.id}`, {
      headers: { 'Authorization': `Bearer ${authToken}` }
    });
  });

  test('Demand filtering and search functionality', async ({ page }) => {
    await page.goto('http://localhost:4321/demands');
    await page.waitForLoadState('networkidle');
    
    // Test status filter
    await page.selectOption('#statusFilter', 'PENDING');
    await page.waitForTimeout(500);
    
    // Verify only pending demands are shown
    const pendingRows = await page.locator('tr[data-demand-id]').count();
    if (pendingRows > 0) {
      const statuses = await page.locator('td.status').allTextContents();
      statuses.forEach(status => {
        expect(status).toContain('PENDING');
      });
    }
    
    // Test type filter
    await page.selectOption('#statusFilter', ''); // Clear status filter
    await page.selectOption('#typeFilter', 'CREATE_NEW');
    await page.waitForTimeout(500);
    
    // Verify only CREATE_NEW demands are shown
    const createRows = await page.locator('tr[data-demand-id]').count();
    if (createRows > 0) {
      const types = await page.locator('td.type').allTextContents();
      types.forEach(type => {
        expect(type).toContain('CREATE_NEW');
      });
    }
    
    // Test priority filter
    await page.selectOption('#typeFilter', ''); // Clear type filter
    await page.selectOption('#priorityFilter', 'HIGH');
    await page.waitForTimeout(500);
    
    // Test search functionality
    await page.selectOption('#priorityFilter', ''); // Clear priority filter
    await page.fill('#searchInput', 'E2E Test');
    await page.waitForTimeout(500);
    
    // Verify search results contain the search term
    const searchResults = await page.locator('tr[data-demand-id]').count();
    if (searchResults > 0) {
      const titles = await page.locator('td.title').allTextContents();
      titles.forEach(title => {
        expect(title.toLowerCase()).toContain('e2e test');
      });
    }
  });

  test('Demand validation and error handling', async ({ page }) => {
    await page.goto('http://localhost:4321/demands');
    await page.waitForLoadState('networkidle');
    
    // Try to create demand without required fields
    await page.click('button:has-text("Create New Demand")');
    await page.click('button:has-text("Submit Demand")');
    
    // Check for validation errors
    await expect(page.locator('.error-message')).toBeVisible();
    await expect(page.locator('.error-message')).toContainText(/required|missing/i);
    
    // Test invalid demand type validation
    await page.selectOption('#demandType', 'CREATE_NEW');
    await page.fill('#title', 'Validation Test');
    await page.fill('#description', 'Testing validation');
    await page.selectOption('#priority', 'LOW');
    await page.fill('#requestor', 'Tester');
    await page.fill('#justification', 'Testing');
    
    // Don't fill new asset information for CREATE_NEW type
    await page.click('button:has-text("Submit Demand")');
    
    // Should show error for missing asset information
    await expect(page.locator('.error-message')).toContainText(/asset information required/i);
    
    // Cancel the form
    await page.click('button:has-text("Cancel")');
  });

  test('Risk assessment with demand integration', async ({ page, request }) => {
    // Create an approved demand via API for testing
    const demandResponse = await request.post('/api/demands', {
      headers: { 'Authorization': `Bearer ${authToken}` },
      data: {
        type: 'CREATE_NEW',
        title: 'E2E Risk Assessment Test Demand',
        description: 'Demand for testing risk assessment integration',
        status: 'PENDING',
        priority: 'HIGH',
        requestor: 'E2E Tester',
        justification: 'Testing risk assessment creation',
        newAssetInfo: {
          name: 'E2E-RA-Test-Asset',
          description: 'Asset for risk assessment testing',
          type: 'DATABASE',
          criticality: 'HIGH',
          owner: 'Test Team',
          location: 'Test Environment'
        }
      }
    });
    
    const testDemand = await demandResponse.json();
    
    // Approve the demand via API
    await request.post(`/api/demands/${testDemand.id}/approve`, {
      headers: { 'Authorization': `Bearer ${authToken}` }
    });
    
    // Navigate to risk assessments
    await page.goto('http://localhost:4321/risk-assessments');
    await page.waitForLoadState('networkidle');
    
    // Verify the approved demand appears in the dropdown
    await page.click('button:has-text("Create New Risk Assessment")');
    
    const demandOptions = await page.locator('#demandId option').allTextContents();
    const demandFound = demandOptions.some(option => 
      option.includes('E2E Risk Assessment Test Demand')
    );
    expect(demandFound).toBeTruthy();
    
    // Clean up
    await request.delete(`/api/demands/${testDemand.id}`, {
      headers: { 'Authorization': `Bearer ${authToken}` }
    });
  });

  test('Demand summary and statistics', async ({ page }) => {
    await page.goto('http://localhost:4321/demands');
    await page.waitForLoadState('networkidle');
    
    // Check if summary section is visible
    await expect(page.locator('.demand-summary')).toBeVisible();
    
    // Verify statistics are displayed
    await expect(page.locator('.total-demands')).toBeVisible();
    await expect(page.locator('.pending-demands')).toBeVisible();
    await expect(page.locator('.approved-demands')).toBeVisible();
    await expect(page.locator('.in-progress-demands')).toBeVisible();
    await expect(page.locator('.completed-demands')).toBeVisible();
    
    // Verify counts are numeric
    const totalCount = await page.locator('.total-demands .count').textContent();
    expect(Number(totalCount)).toBeGreaterThanOrEqual(0);
  });

  test('Demand permissions and access control', async ({ page, request }) => {
    // Logout current admin user
    await page.goto('http://localhost:4321/logout');
    
    // Login as normal user
    await page.goto('http://localhost:4321/login');
    await login(page, 'normaluser', 'password');
    
    // Navigate to demands
    await page.goto('http://localhost:4321/demands');
    await page.waitForLoadState('networkidle');
    
    // Normal user should be able to view demands
    await expect(page.locator('h1')).toContainText('Demand Management');
    
    // Check if approval buttons are disabled/hidden for normal user
    const approveButtons = await page.locator('button[data-action="approve"]').count();
    if (approveButtons > 0) {
      // If visible, they should be disabled
      const firstButton = page.locator('button[data-action="approve"]').first();
      await expect(firstButton).toBeDisabled();
    }
    
    // Try to create a demand as normal user
    await page.click('button:has-text("Create New Demand")');
    
    // Should be able to fill and submit
    await page.selectOption('#demandType', 'CHANGE');
    await page.fill('#title', 'Normal User Test Demand');
    await page.fill('#description', 'Testing normal user permissions');
    await page.selectOption('#priority', 'LOW');
    await page.fill('#requestor', 'normaluser');
    await page.fill('#justification', 'Testing');
    
    // The form should be submittable by normal users
    const submitButton = page.locator('button:has-text("Submit Demand")');
    await expect(submitButton).toBeEnabled();
    
    // Cancel without submitting
    await page.click('button:has-text("Cancel")');
  });
});

test.describe('Demand Management API Tests', () => {
  let authToken: string;

  test.beforeAll(async ({ request }) => {
    const loginResponse = await request.post('/api/auth/login', {
      data: {
        username: 'adminuser',
        password: 'password'
      }
    });
    const loginData = await loginResponse.json();
    authToken = loginData.token;
  });

  test('API: Create demand with validation', async ({ request }) => {
    // Test missing required fields
    const invalidResponse = await request.post('/api/demands', {
      headers: { 'Authorization': `Bearer ${authToken}` },
      data: {
        type: 'CREATE_NEW'
        // Missing required fields
      }
    });
    
    expect(invalidResponse.status()).toBe(400);
    
    // Test valid demand creation
    const validResponse = await request.post('/api/demands', {
      headers: { 'Authorization': `Bearer ${authToken}` },
      data: {
        type: 'CREATE_NEW',
        title: 'API Test Demand',
        description: 'Testing API validation',
        priority: 'MEDIUM',
        requestor: 'API Tester',
        justification: 'API Testing',
        newAssetInfo: {
          name: 'API-Test-Asset',
          description: 'Asset created via API',
          type: 'SERVICE',
          criticality: 'LOW',
          owner: 'API Team',
          location: 'Cloud'
        }
      }
    });
    
    expect(validResponse.status()).toBe(201);
    const demand = await validResponse.json();
    expect(demand.id).toBeDefined();
    
    // Clean up
    await request.delete(`/api/demands/${demand.id}`, {
      headers: { 'Authorization': `Bearer ${authToken}` }
    });
  });

  test('API: Demand state transitions', async ({ request }) => {
    // Create a demand
    const createResponse = await request.post('/api/demands', {
      headers: { 'Authorization': `Bearer ${authToken}` },
      data: {
        type: 'CHANGE',
        title: 'State Transition Test',
        description: 'Testing state transitions',
        priority: 'HIGH',
        requestor: 'Tester',
        justification: 'Testing',
        assetId: 1 // Assuming asset with ID 1 exists
      }
    });
    
    const demand = await createResponse.json();
    expect(demand.status).toBe('PENDING');
    
    // Approve the demand
    const approveResponse = await request.post(`/api/demands/${demand.id}/approve`, {
      headers: { 'Authorization': `Bearer ${authToken}` }
    });
    expect(approveResponse.status()).toBe(200);
    
    const approvedDemand = await approveResponse.json();
    expect(approvedDemand.status).toBe('APPROVED');
    
    // Try to reject an already approved demand (should fail)
    const rejectResponse = await request.post(`/api/demands/${demand.id}/reject`, {
      headers: { 'Authorization': `Bearer ${authToken}` },
      data: { reason: 'Testing rejection' }
    });
    expect(rejectResponse.status()).toBe(400);
    
    // Clean up
    await request.delete(`/api/demands/${demand.id}`, {
      headers: { 'Authorization': `Bearer ${authToken}` }
    });
  });

  test('API: Risk assessment demand relationship', async ({ request }) => {
    // Create and approve a demand
    const demandResponse = await request.post('/api/demands', {
      headers: { 'Authorization': `Bearer ${authToken}` },
      data: {
        type: 'CREATE_NEW',
        title: 'RA Relationship Test',
        description: 'Testing RA relationship',
        priority: 'MEDIUM',
        requestor: 'Tester',
        justification: 'Testing',
        newAssetInfo: {
          name: 'RA-Test-Asset',
          description: 'Asset for RA testing',
          type: 'SERVER',
          criticality: 'MEDIUM',
          owner: 'Test Team',
          location: 'DC'
        }
      }
    });
    
    const demand = await demandResponse.json();
    
    // Approve it
    await request.post(`/api/demands/${demand.id}/approve`, {
      headers: { 'Authorization': `Bearer ${authToken}` }
    });
    
    // Create risk assessment for the demand
    const raResponse = await request.post('/api/risk-assessments', {
      headers: { 'Authorization': `Bearer ${authToken}` },
      data: {
        demandId: demand.id,
        name: 'Test Risk Assessment',
        description: 'Testing demand relationship',
        riskLevel: 'MEDIUM',
        likelihood: 3,
        impact: 3,
        mitigationMeasures: 'Test measures',
        residualRisk: 'LOW'
      }
    });
    
    expect(raResponse.status()).toBe(201);
    const riskAssessment = await raResponse.json();
    expect(riskAssessment.demandId).toBe(demand.id);
    
    // Verify demand status changed to IN_PROGRESS
    const demandStatusResponse = await request.get(`/api/demands/${demand.id}`, {
      headers: { 'Authorization': `Bearer ${authToken}` }
    });
    const updatedDemand = await demandStatusResponse.json();
    expect(updatedDemand.status).toBe('IN_PROGRESS');
    
    // Clean up
    await request.delete(`/api/risk-assessments/${riskAssessment.id}`, {
      headers: { 'Authorization': `Bearer ${authToken}` }
    });
    await request.delete(`/api/demands/${demand.id}`, {
      headers: { 'Authorization': `Bearer ${authToken}` }
    });
  });
});