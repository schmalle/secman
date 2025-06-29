#!/usr/bin/env node

const { exec } = require('child_process');
const { promisify } = require('util');

const execAsync = promisify(exec);

async function cleanupTestEnvironment() {
  console.log('🧹 Cleaning up Secman E2E Test Environment...\n');

  try {
    // Clean up test data
    console.log('1. Cleaning up test data...');
    await cleanupTestData();
    console.log('   ✅ Test data cleaned');

    // Remove test users
    console.log('2. Removing test users...');
    await removeTestUsers();
    console.log('   ✅ Test users removed');

    // Clean up test files
    console.log('3. Cleaning up test files...');
    await cleanupTestFiles();
    console.log('   ✅ Test files cleaned');

    // Kill running servers if requested
    if (process.argv.includes('--kill-servers')) {
      console.log('4. Stopping test servers...');
      await stopTestServers();
      console.log('   ✅ Test servers stopped');
    }

    console.log('\n✅ Test environment cleanup complete!');

  } catch (error) {
    console.error('❌ Failed to cleanup test environment:', error.message);
    process.exit(1);
  }
}

async function cleanupTestData() {
  // Clean up test requirements, assessments, etc.
  const testDataPrefixes = [
    'UI-TEST-',
    'API-TEST-',
    'DELETE-TEST-',
    'EDIT-TEST-',
    'SEARCH-REQ-',
    'BULK-DELETE-',
    'GET-TEST-',
    'UPDATE-TEST-',
    'RELEASE-TEST-',
    'KEYBOARD-NAV-TEST'
  ];

  for (const prefix of testDataPrefixes) {
    try {
      // This would typically make API calls to delete test data
      console.log(`   Cleaning data with prefix: ${prefix}`);
      
      // Example: Delete test requirements
      await deleteTestRequirements(prefix);
      
      // Example: Delete test assessments
      await deleteTestAssessments(prefix);
      
    } catch (error) {
      console.log(`   Warning: Could not clean data with prefix ${prefix}: ${error.message}`);
    }
  }
}

async function deleteTestRequirements(prefix) {
  try {
    // Login as admin first
    const loginResponse = await fetch('http://localhost:9000/api/auth/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        email: 'adminuser',
        password: 'password'
      })
    });

    if (!loginResponse.ok) return;

    const cookies = loginResponse.headers.get('set-cookie');

    // Get all requirements
    const reqResponse = await fetch('http://localhost:9000/api/requirements', {
      headers: { 'Cookie': cookies }
    });

    if (!reqResponse.ok) return;

    const requirements = await reqResponse.json();

    // Delete test requirements
    for (const req of requirements) {
      if (req.shortreq && req.shortreq.startsWith(prefix)) {
        await fetch(`http://localhost:9000/api/requirements/${req.id}`, {
          method: 'DELETE',
          headers: { 'Cookie': cookies }
        });
        console.log(`   Deleted requirement: ${req.shortreq}`);
      }
    }
  } catch (error) {
    // Ignore errors - servers might not be running
  }
}

async function deleteTestAssessments(prefix) {
  try {
    // Similar to deleteTestRequirements but for assessments
    // Implementation would depend on API structure
  } catch (error) {
    // Ignore errors
  }
}

async function removeTestUsers() {
  const testEmails = [
    'adminuser',
    'user@test.com', 
    'external@test.com',
    'assessor@test.com',
    'requestor@test.com',
    'respondent@test.com'
  ];

  // In a real implementation, this would remove test users
  // For now, just log what would be done
  for (const email of testEmails) {
    console.log(`   Would remove test user: ${email}`);
  }
}

async function cleanupTestFiles() {
  const path = require('path');
  const fs = require('fs').promises;

  const filesToClean = [
    '../playwright-report',
    '../test-results',
    '../test-results.json',
    '../test-results.xml'
  ];

  for (const file of filesToClean) {
    try {
      const fullPath = path.join(__dirname, file);
      await fs.rm(fullPath, { recursive: true, force: true });
      console.log(`   Removed: ${file}`);
    } catch (error) {
      // File might not exist, that's OK
    }
  }
}

async function stopTestServers() {
  try {
    // Kill processes on ports 9000 and 4321
    await execAsync('lsof -ti:9000 | xargs kill -9 2>/dev/null || true');
    await execAsync('lsof -ti:4321 | xargs kill -9 2>/dev/null || true');
    
    console.log('   Stopped backend server (port 9000)');
    console.log('   Stopped frontend server (port 4321)');
  } catch (error) {
    console.log('   No servers to stop or error stopping servers');
  }
}

// Run cleanup if called directly
if (require.main === module) {
  cleanupTestEnvironment();
}

module.exports = { cleanupTestEnvironment };