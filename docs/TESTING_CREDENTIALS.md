# Testing Credentials and Configuration Guide

This document provides guidance on credential storage and configuration for running e2e tests that depend on AI services.

## Overview

The SecMan application includes AI-powered norm mapping functionality that requires external API credentials. This guide explains how to configure these credentials for testing environments while maintaining security best practices.

## AI Service Requirements

The norm mapping functionality uses the existing `TranslationService` which supports OpenRouter API or compatible OpenAI-style APIs. For testing, you need:

1. **API Key**: From your AI service provider (OpenRouter, OpenAI, etc.)
2. **Model Name**: The AI model to use (e.g., "anthropic/claude-3-sonnet", "gpt-4")
3. **Base URL**: API endpoint (e.g., "https://openrouter.ai/api/v1")

## Credential Storage Options

### Option 1: Environment Variables (Recommended)

Create environment variables for test credentials:

```bash
# AI Service Configuration
export SECMAN_TEST_AI_API_KEY="your-api-key-here"
export SECMAN_TEST_AI_BASE_URL="https://openrouter.ai/api/v1"
export SECMAN_TEST_AI_MODEL="anthropic/claude-3-sonnet"
```

### Option 2: Local Configuration File

Create a test-specific configuration file that is **NOT** committed to version control:

**File: `tests/.env.local` (add to .gitignore)**
```env
SECMAN_TEST_AI_API_KEY=your-api-key-here
SECMAN_TEST_AI_BASE_URL=https://openrouter.ai/api/v1
SECMAN_TEST_AI_MODEL=anthropic/claude-3-sonnet
```

### Option 3: CI/CD Secrets

For continuous integration, store credentials as encrypted secrets:

#### GitHub Actions
```yaml
# .github/workflows/test.yml
env:
  SECMAN_TEST_AI_API_KEY: ${{ secrets.SECMAN_TEST_AI_API_KEY }}
  SECMAN_TEST_AI_BASE_URL: ${{ secrets.SECMAN_TEST_AI_BASE_URL }}
  SECMAN_TEST_AI_MODEL: ${{ secrets.SECMAN_TEST_AI_MODEL }}
```

#### GitLab CI
```yaml
# .gitlab-ci.yml
variables:
  SECMAN_TEST_AI_API_KEY: $SECMAN_TEST_AI_API_KEY
  SECMAN_TEST_AI_BASE_URL: $SECMAN_TEST_AI_BASE_URL
  SECMAN_TEST_AI_MODEL: $SECMAN_TEST_AI_MODEL
```

## Test Configuration Setup

### 1. Database Setup for Tests

Ensure your test database has a valid translation configuration:

```sql
-- Insert test AI configuration
INSERT INTO translation_config (
    base_url, 
    api_key, 
    model_name, 
    max_tokens, 
    temperature, 
    is_active, 
    created_at, 
    updated_at
) VALUES (
    'https://openrouter.ai/api/v1',
    'your-test-api-key',
    'anthropic/claude-3-sonnet',
    1000,
    0.3,
    true,
    NOW(),
    NOW()
);
```

### 2. Test Environment Script

Create a setup script for test environments:

**File: `tests/scripts/setup-ai-config.js`**
```javascript
const { execSync } = require('child_process');

function setupAIConfig() {
  const apiKey = process.env.SECMAN_TEST_AI_API_KEY;
  const baseUrl = process.env.SECMAN_TEST_AI_BASE_URL || 'https://openrouter.ai/api/v1';
  const model = process.env.SECMAN_TEST_AI_MODEL || 'anthropic/claude-3-sonnet';
  
  if (!apiKey) {
    console.warn('⚠️  No AI API key found. AI-dependent tests will be skipped.');
    return false;
  }
  
  // Configure the test database with AI settings
  const sqlCommand = `
    INSERT INTO translation_config (base_url, api_key, model_name, max_tokens, temperature, is_active, created_at, updated_at)
    VALUES ('${baseUrl}', '${apiKey}', '${model}', 1000, 0.3, true, NOW(), NOW())
    ON DUPLICATE KEY UPDATE 
      api_key = '${apiKey}',
      base_url = '${baseUrl}',
      model_name = '${model}',
      is_active = true,
      updated_at = NOW();
  `;
  
  try {
    execSync(`mysql -u secman -pCHANGEME secman -e "${sqlCommand}"`);
    console.log('✅ AI configuration set up successfully for tests');
    return true;
  } catch (error) {
    console.error('❌ Failed to set up AI configuration:', error.message);
    return false;
  }
}

module.exports = { setupAIConfig };
```

## Security Best Practices

### 1. API Key Protection
- **NEVER** commit API keys to version control
- Use different API keys for testing vs. production
- Regularly rotate test API keys
- Use restricted API keys with minimal permissions

### 2. .gitignore Entries
Ensure these patterns are in your `.gitignore`:
```gitignore
# Test credentials and config
tests/.env.local
tests/credentials/
*.credentials
.env.test.local
```

### 3. CI/CD Configuration
- Store credentials as encrypted secrets
- Use different credentials for different environments
- Implement credential rotation policies
- Monitor API usage for anomalies

## Test Execution

### Running Tests with AI Services

```bash
# Set up environment
export SECMAN_TEST_AI_API_KEY="your-key"
export SECMAN_TEST_AI_BASE_URL="https://openrouter.ai/api/v1"
export SECMAN_TEST_AI_MODEL="anthropic/claude-3-sonnet"

# Run API tests
cd tests
npm run test:api

# Run UI tests
npm run test:ui

# Run all e2e tests
npm run test:e2e
```

### Running Tests without AI Services

Tests are designed to gracefully handle missing AI configuration:

```bash
# Tests will run but skip AI-dependent functionality
cd tests
npm run test:api

# AI mapping tests will show appropriate warnings
```

## Troubleshooting

### Common Issues

1. **"No active translation configuration found"**
   - Ensure AI configuration is properly inserted into test database
   - Check that `is_active = true` in the configuration

2. **"API key authentication failed"**
   - Verify API key is correct and has sufficient permissions
   - Check API key quota and usage limits

3. **"Model not found"**
   - Verify the model name is supported by your API provider
   - Check for typos in model name configuration

4. **"Rate limit exceeded"**
   - Implement backoff and retry logic in tests
   - Use different API keys for parallel test execution

### Debug Mode

Enable debug logging for AI service issues:

```bash
export DEBUG=secman:ai,secman:mapping
npm run test:api
```

## Cost Management

### API Usage Control
- Set strict rate limits for test API keys
- Monitor API usage and costs regularly
- Use smaller, faster models for basic testing
- Implement circuit breakers for expensive operations

### Test Optimization
- Cache AI responses for repeated test scenarios
- Use mocked responses for most tests
- Only test real AI integration in critical paths
- Implement test data fixtures for AI responses

## Alternative Testing Strategies

### 1. Mock AI Responses
For most tests, use mocked AI responses instead of real API calls:

```javascript
// In test setup
beforeAll(() => {
  jest.mock('../services/NormMappingService', () => ({
    suggestMissingMappings: jest.fn().mockResolvedValue({
      suggestions: [
        {
          requirementId: 1,
          suggestions: [
            { standard: 'NIST SP 800-53', confidence: 4, reasoning: 'Mock response' }
          ]
        }
      ]
    })
  }));
});
```

### 2. Contract Testing
Use contract testing to verify API integration without making actual calls:

```javascript
const mockResponses = require('./fixtures/ai-responses.json');

test('should handle AI service contract correctly', () => {
  const response = mockResponses.normMappingSuggestion;
  expect(response).toMatchSchema(normMappingResponseSchema);
});
```

### 3. Integration Test Environments
Set up dedicated test environments with controlled AI service instances:
- Use local AI models for testing
- Implement AI service mocks that return realistic responses
- Create test-specific AI configurations with limited capabilities

---

## Summary

This credential storage strategy provides:
1. **Security**: Keeps credentials out of version control
2. **Flexibility**: Supports multiple deployment scenarios
3. **Maintainability**: Clear documentation and setup procedures
4. **Cost Control**: Guidance on managing AI service costs
5. **Reliability**: Fallback strategies when AI services are unavailable

Choose the credential storage method that best fits your deployment environment and security requirements.