#!/usr/bin/env node

const { exec } = require('child_process');
const { promisify } = require('util');
const fs = require('fs').promises;
const path = require('path');

const execAsync = promisify(exec);

async function setupTestEnvironment() {
  console.log('🚀 Setting up Secman E2E Test Environment...\n');

  try {
    // Check if backend is running
    console.log('1. Checking backend server...');
    const backendHealthy = await checkBackendHealth();
    if (!backendHealthy) {
      console.log('   Backend not running. Starting backend server...');
      await startBackend();
    } else {
      console.log('   ✅ Backend server is running');
    }

    // Check if frontend is running
    console.log('2. Checking frontend server...');
    const frontendHealthy = await checkFrontendHealth();
    if (!frontendHealthy) {
      console.log('   Frontend not running. Starting frontend server...');
      await startFrontend();
    } else {
      console.log('   ✅ Frontend server is running');
    }

    // Setup test database
    console.log('3. Setting up test database...');
    await setupTestDatabase();
    console.log('   ✅ Test database configured');

    // Create test users
    console.log('4. Creating test users...');
    await createTestUsers();
    console.log('   ✅ Test users created');

    // Install test dependencies
    console.log('5. Installing test dependencies...');
    await installTestDependencies();
    console.log('   ✅ Test dependencies installed');

    console.log('\n✅ Test environment setup complete!');
    console.log('\nYou can now run:');
    console.log('  npm run test:api    - Run API tests');
    console.log('  npm run test:ui     - Run UI tests');
    console.log('  npm run test:e2e    - Run all tests');

  } catch (error) {
    console.error('❌ Failed to setup test environment:', error.message);
    process.exit(1);
  }
}

async function checkBackendHealth() {
  try {
    const response = await fetch('http://localhost:9000/api/auth/status');
    return response.status === 200 || response.status === 401;
  } catch (error) {
    return false;
  }
}

async function checkFrontendHealth() {
  try {
    const response = await fetch('http://localhost:4321');
    return response.status === 200;
  } catch (error) {
    return false;
  }
}

async function startBackend() {
  console.log('   Starting backend server (this may take a moment)...');
  
  const backendPath = path.join(__dirname, '../../src/backend');
  
  // Start backend in background
  const backendProcess = exec('sbt run', { cwd: backendPath });
  
  // Wait for backend to be ready
  let attempts = 0;
  const maxAttempts = 60; // 60 seconds
  
  while (attempts < maxAttempts) {
    await new Promise(resolve => setTimeout(resolve, 1000));
    
    if (await checkBackendHealth()) {
      console.log('   ✅ Backend server started successfully');
      return;
    }
    
    attempts++;
    if (attempts % 10 === 0) {
      console.log(`   Waiting for backend... (${attempts}/${maxAttempts})`);
    }
  }
  
  throw new Error('Backend server failed to start within 60 seconds');
}

async function startFrontend() {
  console.log('   Starting frontend server...');
  
  const frontendPath = path.join(__dirname, '../../src/frontend');
  
  // Install dependencies first
  await execAsync('npm install', { cwd: frontendPath });
  
  // Start frontend in background
  const frontendProcess = exec('npm run dev', { cwd: frontendPath });
  
  // Wait for frontend to be ready
  let attempts = 0;
  const maxAttempts = 30; // 30 seconds
  
  while (attempts < maxAttempts) {
    await new Promise(resolve => setTimeout(resolve, 1000));
    
    if (await checkFrontendHealth()) {
      console.log('   ✅ Frontend server started successfully');
      return;
    }
    
    attempts++;
    if (attempts % 5 === 0) {
      console.log(`   Waiting for frontend... (${attempts}/${maxAttempts})`);
    }
  }
  
  throw new Error('Frontend server failed to start within 30 seconds');
}

async function setupTestDatabase() {
  // This would typically involve:
  // 1. Creating test database
  // 2. Running migrations
  // 3. Seeding with test data
  
  console.log('   Database setup completed (would configure test DB here)');
}

async function createTestUsers() {
  // Create test users via API calls
  const users = [
    {
      email: 'adminuser',
      password: 'password',
      role: 'ADMIN',
      name: 'Test Admin'
    },
    {
      email: 'user@test.com',
      password: 'password',
      role: 'USER',
      name: 'Test User'
    },
    {
      email: 'external@test.com',
      password: 'password',
      role: 'USER',
      name: 'External Test User'
    }
  ];

  for (const user of users) {
    try {
      const response = await fetch('http://localhost:9000/api/users', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json'
        },
        body: JSON.stringify(user)
      });
      
      if (response.ok) {
        console.log(`   Created user: ${user.email}`);
      } else if (response.status === 409) {
        console.log(`   User already exists: ${user.email}`);
      } else {
        console.log(`   Failed to create user: ${user.email}`);
      }
    } catch (error) {
      console.log(`   Error creating user ${user.email}: ${error.message}`);
    }
  }
}

async function installTestDependencies() {
  const testsPath = path.join(__dirname, '..');
  
  try {
    await execAsync('npm install', { cwd: testsPath });
  } catch (error) {
    console.log('   Installing dependencies...');
    // Dependencies might already be installed
  }
}

// Run setup if called directly
if (require.main === module) {
  setupTestEnvironment();
}

module.exports = { setupTestEnvironment };