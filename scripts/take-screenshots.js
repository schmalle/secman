#!/usr/bin/env node

/**
 * Pre-commit screenshot script for Secman
 * Takes screenshots of main UI pages and stores them in /pictures
 * Requires both backend and frontend to be running
 * 
 * Usage:
 *   node take-screenshots.js [--username <username>] [--password <password>]
 *   
 * If no credentials are provided, defaults to adminuser/password
 */

const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');

// Parse command line arguments
function parseArguments() {
  const args = process.argv.slice(2);
  const parsed = {
    username: 'adminuser',
    password: 'password'
  };

  for (let i = 0; i < args.length; i++) {
    switch (args[i]) {
      case '--username':
      case '-u':
        if (i + 1 < args.length) {
          parsed.username = args[i + 1];
          i++;
        }
        break;
      case '--password':
      case '-p':
        if (i + 1 < args.length) {
          parsed.password = args[i + 1];
          i++;
        }
        break;
      case '--help':
      case '-h':
        console.log(`
Screenshot Script for Secman

Usage: node take-screenshots.js [options]

Options:
  -u, --username <username>    Username for authentication (default: adminuser)
  -p, --password <password>    Password for authentication (default: password)
  -h, --help                   Show this help message

Examples:
  node take-screenshots.js
  node take-screenshots.js --username myuser --password mypass
  node take-screenshots.js -u admin -p secret
        `);
        process.exit(0);
        break;
    }
  }

  return parsed;
}

const cliArgs = parseArguments();

// Configuration
const CONFIG = {
  baseUrl: 'http://localhost:4321',
  backendUrl: 'http://localhost:9000',
  screenshots: {
    outputDir: path.join(__dirname, '..', 'pictures'),
    viewport: { width: 1920, height: 1080 },
    fullPage: true
  },
  timeout: 60000,
  credentials: {
    username: cliArgs.username,
    password: cliArgs.password
  }
};

// Pages to screenshot
const PAGES = [
  {
    name: 'login',
    url: '/login',
    title: 'Login Page',
    authRequired: false
  },
  {
    name: 'dashboard',
    url: '/',
    title: 'Main Dashboard',
    authRequired: true
  },
  {
    name: 'assets',
    url: '/assets',
    title: 'Asset Management',
    authRequired: true
  },
  {
    name: 'requirements',
    url: '/requirements',
    title: 'Requirements Management',
    authRequired: true
  },
  {
    name: 'risk-assessments',
    url: '/risk-assessments',
    title: 'Risk Assessment Management',
    authRequired: true
  },
  {
    name: 'reports',
    url: '/reports',
    title: 'Risk Assessment Reports',
    authRequired: true
  },
  {
    name: 'risks',
    url: '/risks',
    title: 'Risk Management',
    authRequired: true
  },
  {
    name: 'standards',
    url: '/standards',
    title: 'Standards Management',
    authRequired: true
  },
  {
    name: 'usecases',
    url: '/usecases',
    title: 'Use Case Management',
    authRequired: true
  }
];

class ScreenshotTaker {
  constructor() {
    this.browser = null;
    this.page = null;
    this.isAuthenticated = false;
  }

  async init() {
    console.log('🚀 Initializing screenshot capture...');
    
    // Ensure output directory exists
    if (!fs.existsSync(CONFIG.screenshots.outputDir)) {
      fs.mkdirSync(CONFIG.screenshots.outputDir, { recursive: true });
    }

    // Launch browser
    this.browser = await chromium.launch({
      headless: true,
      args: ['--no-sandbox', '--disable-setuid-sandbox']
    });

    this.page = await this.browser.newPage({
      viewport: CONFIG.screenshots.viewport
    });

    // Set longer timeout for slow connections
    this.page.setDefaultTimeout(CONFIG.timeout);
  }

  async checkServices() {
    console.log('🔍 Checking if services are running...');
    
    try {
      // Check frontend
      const frontendResponse = await fetch(CONFIG.baseUrl);
      if (!frontendResponse.ok) {
        throw new Error(`Frontend not responding: ${frontendResponse.status}`);
      }
      console.log('✅ Frontend is running');

      // Check backend
      const backendResponse = await fetch(`${CONFIG.backendUrl}/api/health`);
      if (!backendResponse.ok) {
        // Backend might not have health endpoint, try a basic endpoint
        const altResponse = await fetch(`${CONFIG.backendUrl}/api/users`);
        if (!altResponse.ok) {
          console.log('⚠️  Backend health check failed, but continuing...');
        }
      }
      console.log('✅ Backend is running');
    } catch (error) {
      console.error('❌ Service check failed:', error.message);
      console.log('Please ensure both frontend (port 4321) and backend (port 9000) are running');
      throw error;
    }
  }

  async authenticate() {
    if (this.isAuthenticated) return;

    console.log('🔐 Authenticating...');
    
    try {
      await this.page.goto(`${CONFIG.baseUrl}/login`);
      await this.page.waitForLoadState('networkidle');

      // Wait for login form to be visible
      await this.page.waitForSelector('#username', { timeout: 30000 });
      await this.page.waitForSelector('#password', { timeout: 30000 });

      // Fill login form using id selectors
      await this.page.fill('#username', CONFIG.credentials.username);
      await this.page.fill('#password', CONFIG.credentials.password);
      
      // Submit form
      await this.page.click('button[type="submit"]');
      
      // Wait for redirect to dashboard or check for error
      try {
        await this.page.waitForURL(`${CONFIG.baseUrl}/`, { timeout: 30000 });
        await this.page.waitForLoadState('networkidle');
        this.isAuthenticated = true;
        console.log('✅ Authentication successful');
      } catch (urlError) {
        // Check if we're still on login page with error
        const currentUrl = this.page.url();
        if (currentUrl.includes('/login')) {
          const errorElement = await this.page.$('.alert-danger');
          if (errorElement) {
            const errorText = await errorElement.textContent();
            throw new Error(`Login failed: ${errorText}`);
          }
        }
        throw urlError;
      }
    } catch (error) {
      console.error('❌ Authentication failed:', error.message);
      // Add debug info
      const currentUrl = this.page.url();
      console.error(`Current URL: ${currentUrl}`);
      throw error;
    }
  }

  async takeScreenshot(pageConfig) {
    console.log(`📸 Taking screenshot: ${pageConfig.title}`);
    
    try {
      // Authenticate if required
      if (pageConfig.authRequired && !this.isAuthenticated) {
        await this.authenticate();
      }

      // Navigate to page
      await this.page.goto(`${CONFIG.baseUrl}${pageConfig.url}`);
      await this.page.waitForLoadState('networkidle');

      // Wait a bit more for dynamic content
      await this.page.waitForTimeout(2000);

      // Generate filename
      const filename = `${pageConfig.name}.png`;
      const filepath = path.join(CONFIG.screenshots.outputDir, filename);

      // Take screenshot
      await this.page.screenshot({
        path: filepath,
        fullPage: CONFIG.screenshots.fullPage
      });

      console.log(`✅ Screenshot saved: ${filename}`);
      return { success: true, filename, page: pageConfig.name };
    } catch (error) {
      console.error(`❌ Failed to screenshot ${pageConfig.title}:`, error.message);
      return { success: false, error: error.message, page: pageConfig.name };
    }
  }

  async takeAllScreenshots() {
    console.log(`📸 Taking screenshots of ${PAGES.length} pages...`);
    
    const results = [];
    
    for (const pageConfig of PAGES) {
      const result = await this.takeScreenshot(pageConfig);
      results.push(result);
      
      // Small delay between screenshots
      await this.page.waitForTimeout(1000);
    }

    return results;
  }

  async createLatestSymlinks(results) {
    // No longer creating _latest symlinks since we use simple names
    console.log('✅ Using simple filenames, no symlinks needed');
  }

  async cleanup() {
    if (this.browser) {
      await this.browser.close();
    }
  }

  async generateReport(results) {
    const successful = results.filter(r => r.success);
    const failed = results.filter(r => !r.success);
    
    console.log('\n📊 Screenshot Report:');
    console.log(`✅ Successful: ${successful.length}`);
    console.log(`❌ Failed: ${failed.length}`);
    
    if (successful.length > 0) {
      console.log('\n✅ Successfully captured:');
      successful.forEach(r => console.log(`  - ${r.page} (${r.filename})`));
    }
    
    if (failed.length > 0) {
      console.log('\n❌ Failed to capture:');
      failed.forEach(r => console.log(`  - ${r.page}: ${r.error}`));
    }

    // Generate markdown report
    const reportPath = path.join(CONFIG.screenshots.outputDir, 'README.md');
    const reportContent = this.generateMarkdownReport(results);
    fs.writeFileSync(reportPath, reportContent);
    console.log(`\n📝 Report generated: ${reportPath}`);
  }

  generateMarkdownReport(results) {
    const timestamp = new Date().toISOString();
    
    let markdown = `# Secman UI Screenshots\n\n`;
    markdown += `Generated: ${timestamp}\n\n`;
    markdown += `## Latest Screenshots\n\n`;
    
    for (const result of results) {
      if (result.success) {
        markdown += `### ${result.page.charAt(0).toUpperCase() + result.page.slice(1)} Page\n\n`;
        markdown += `![${result.page}](${result.page}.png)\n\n`;
      }
    }
    
    markdown += `## All Screenshots\n\n`;
    markdown += `| Page | Status | Filename |\n`;
    markdown += `|------|--------|----------|\n`;
    
    for (const result of results) {
      const status = result.success ? '✅' : '❌';
      const filename = result.success ? result.filename : result.error;
      markdown += `| ${result.page} | ${status} | ${filename} |\n`;
    }
    
    return markdown;
  }
}

async function main() {
  const screenshotTaker = new ScreenshotTaker();
  
  console.log(`🚀 Starting screenshot capture with user: ${CONFIG.credentials.username}`);
  
  try {
    await screenshotTaker.init();
    await screenshotTaker.checkServices();
    
    const results = await screenshotTaker.takeAllScreenshots();
    
    await screenshotTaker.generateReport(results);
    
    console.log('\n🎉 Screenshot capture completed!');
    console.log(`📁 Screenshots saved to: ${CONFIG.screenshots.outputDir}`);
    
  } catch (error) {
    console.error('\n💥 Screenshot capture failed:', error.message);
    process.exit(1);
  } finally {
    await screenshotTaker.cleanup();
  }
}

// Run if called directly
if (require.main === module) {
  main().catch(console.error);
}

module.exports = { ScreenshotTaker, CONFIG, PAGES };