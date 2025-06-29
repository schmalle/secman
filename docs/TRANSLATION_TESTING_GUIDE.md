# Secman Translation Feature Testing Guide

## Overview

This guide provides comprehensive instructions for testing the Secman translation feature using the command-line test script. The translation feature enables multilingual document generation using AI-powered translation services via OpenRouter API.

## Quick Start

### Prerequisites

1. **System Dependencies**
   ```bash
   # Install required tools (if not already installed)
   # On macOS:
   brew install curl jq
   
   # On Ubuntu/Debian:
   sudo apt-get install curl jq
   
   # On CentOS/RHEL:
   sudo yum install curl jq
   ```

2. **Secman Environment**
   - Backend server running on port 9000 (default)
   - Frontend server running on port 4321 (default)
   - MariaDB database with sample data
   - Admin user account for testing

3. **Optional: OpenRouter API Key**
   ```bash
   export SECMAN_OPENROUTER_API_KEY="your-openrouter-api-key-here"
   ```

### Basic Usage

```bash
# Run all translation tests
./test-translation.sh

# Run quick essential tests only
./test-translation.sh quick

# Run with custom language (German)
./test-translation.sh --language de

# Run with verbose output
./test-translation.sh --verbose
```

## Test Script Features

### Test Categories

1. **Connectivity Tests** - Verify system connectivity and authentication
2. **Translation Configuration Tests** - Test CRUD operations for translation configurations
3. **Translation Tests** - Test actual translation functionality and document export

### Supported Commands

| Command | Description | Usage |
|---------|-------------|-------|
| `all` | Run comprehensive test suite (default) | `./test-translation.sh all` |
| `quick` | Run essential tests only | `./test-translation.sh quick` |
| `config` | Run translation configuration tests only | `./test-translation.sh config` |
| `translate` | Run translation functionality tests only | `./test-translation.sh translate` |
| `help` | Show help information | `./test-translation.sh help` |

### Configuration Options

#### Command Line Options

```bash
./test-translation.sh [OPTIONS] [COMMAND]

Options:
  -u, --base-url URL          Backend base URL (default: http://localhost:9000)
  -f, --frontend-url URL      Frontend URL (default: http://localhost:4321)
  -l, --language LANG         Target language for tests (default: de)
  -o, --output-dir DIR        Output directory (default: ./test-results)
  -v, --verbose               Enable verbose logging
  -q, --quiet                 Quiet mode (errors only)
  --user EMAIL                Test user email (default: admin@secman.local)
  --password PASS             Test user password (default: admin123)
```

#### Environment Variables

```bash
# Core configuration
export SECMAN_TEST_BASE_URL="http://localhost:9000"
export SECMAN_TEST_FRONTEND_URL="http://localhost:4321"
export SECMAN_TEST_USER="admin@secman.local"
export SECMAN_TEST_PASSWORD="admin123"

# Test configuration
export SECMAN_TEST_LANGUAGE="de"  # Target language (de=German, en=English)
export SECMAN_TEST_OUTPUT_DIR="./my-test-results"
export SECMAN_TEST_LOG_LEVEL="DEBUG"  # ERROR, WARN, INFO, DEBUG

# Translation API key (optional for configuration tests)
export SECMAN_OPENROUTER_API_KEY="your-api-key-here"
```

## Test Scenarios

### 1. Translation Configuration Management

#### Test Cases Covered:
- **TC-001**: Create new translation configuration
- **TC-002**: Retrieve all translation configurations
- **TC-003**: Get active translation configuration
- **TC-004**: Update translation configuration
- **TC-005**: Test translation configuration endpoint

#### Expected Results:
- Configuration CRUD operations succeed
- Active configuration management works correctly
- Configuration validation prevents invalid settings
- API key security is maintained

### 2. Translation Functionality

#### Test Cases Covered:
- **TR-001**: Get requirements for translation
- **TR-002**: Export all requirements with translation
- **TR-003**: Export use case requirements with translation

#### Expected Results:
- Requirements are successfully retrieved
- Translated DOCX files are generated
- Document structure and formatting are preserved
- Translation quality is maintained

### 3. System Integration

#### Test Cases Covered:
- **CONN-001**: Backend connectivity test
- **CONN-002**: Frontend connectivity test
- **CONN-003**: Authentication requirement test

#### Expected Results:
- All system components are accessible
- Authentication is properly enforced
- API endpoints respond correctly

## Understanding Test Results

### Test Output Format

```
[2024-06-23 10:30:15] [INFO] Running test: TC-001 - Create translation configuration
[2024-06-23 10:30:16] [SUCCESS] Test passed: TC-001
[2024-06-23 10:30:16] [INFO] Running test: TC-002 - Get all translation configurations
[2024-06-23 10:30:17] [SUCCESS] Test passed: TC-002
```

### Test Report

After execution, a detailed report is generated in the output directory:

```
translation_test_report_20240623_103045.txt
```

The report includes:
- Execution summary (duration, pass/fail counts)
- Configuration details
- Individual test results
- Success rate calculation

### Output Files

```
test-results/
├── translation_test_report_20240623_103045.txt
├── translated_export_de.docx
├── translated_usecase_1_de.docx
└── ... (other generated files)
```

## Troubleshooting

### Common Issues and Solutions

#### 1. Authentication Failures

**Error**: `Authentication failed: connection_failed`

**Solutions**:
- Verify backend server is running on the correct port
- Check user credentials (email/password)
- Ensure user has appropriate permissions
- Verify CSRF token handling

```bash
# Debug authentication
curl -v http://localhost:9000/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"admin@secman.local","password":"admin123"}'
```

#### 2. Translation Configuration Issues

**Error**: `Failed to create translation config`

**Solutions**:
- Verify OpenRouter API key is valid
- Check API key format and permissions
- Ensure translation service endpoints are accessible
- Validate configuration parameters

```bash
# Test OpenRouter API key manually
curl -H "Authorization: Bearer $SECMAN_OPENROUTER_API_KEY" \
  https://openrouter.ai/api/v1/models
```

#### 3. Document Export Problems

**Error**: `Failed to export translated DOCX`

**Solutions**:
- Ensure requirements data exists in database
- Verify translation configuration is active
- Check disk space for output files
- Validate file permissions

```bash
# Check requirements data
curl -H "Cookie: [session-cookie]" \
  http://localhost:9000/api/requirements | jq '. | length'
```

#### 4. Network Connectivity Issues

**Error**: `Backend connectivity failed`

**Solutions**:
- Verify server URLs and ports
- Check firewall settings
- Test network connectivity
- Ensure services are running

```bash
# Test basic connectivity
curl -I http://localhost:9000/
curl -I http://localhost:4321/
```

### Debug Mode

Enable debug mode for detailed troubleshooting:

```bash
./test-translation.sh --verbose all
```

This provides:
- Detailed HTTP request/response information
- Configuration validation details
- Step-by-step execution logging
- Error context and debugging information

### Manual Testing

For manual verification of specific functionality:

```bash
# Test translation configuration endpoint
curl -X GET \
  -H "Cookie: [your-session-cookie]" \
  http://localhost:9000/api/translation-config

# Test document export with translation
curl -X GET \
  -H "Cookie: [your-session-cookie]" \
  -o "manual_test.docx" \
  "http://localhost:9000/api/requirements/export/docx/translated/de"
```

## Advanced Usage

### Custom Test Scenarios

You can extend the test script with custom scenarios:

```bash
# Create custom test function
test_custom_scenario() {
    # Your custom test logic here
    return 0  # Success
}

# Add to test suite
run_test "CUSTOM-001" "test_custom_scenario" "Custom test description"
```

### Performance Testing

For performance evaluation:

```bash
# Time the test execution
time ./test-translation.sh quick

# Monitor system resources during testing
top -p $(pgrep -f java)  # Monitor backend process
```

### Automated Testing Integration

#### Jenkins Integration

```bash
#!/bin/bash
# Jenkins test script
cd /path/to/secman
export SECMAN_OPENROUTER_API_KEY="$JENKINS_OPENROUTER_KEY"
./test-translation.sh all --output-dir "$WORKSPACE/test-results"
```

#### GitHub Actions Integration

```yaml
name: Translation Tests
on: [push, pull_request]
jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v2
      - name: Setup test environment
        run: |
          # Start Secman services
          docker-compose up -d
      - name: Run translation tests
        env:
          SECMAN_OPENROUTER_API_KEY: ${{ secrets.OPENROUTER_API_KEY }}
        run: ./test-translation.sh all
```

### Load Testing

For testing with large datasets:

```bash
# Test with specific language and large dataset
export SECMAN_TEST_LANGUAGE="de"
./test-translation.sh translate --verbose

# Monitor performance metrics
./test-translation.sh all 2>&1 | grep -E "(duration|seconds|timing)"
```

## Security Considerations

### API Key Management

- Never commit API keys to version control
- Use environment variables for sensitive data
- Rotate API keys regularly
- Monitor API usage and quotas

### Test Data Security

- Use test-specific user accounts
- Avoid production data in tests
- Clean up test configurations after execution
- Validate input sanitization

### Access Control Testing

The script includes tests for:
- Authentication requirements
- Session management
- Role-based access control
- CSRF protection

## Contribution Guidelines

### Adding New Tests

1. Define test scenario in `test-translation-scenarios.md`
2. Implement test function in `test-translation.sh`
3. Add test to appropriate test suite
4. Update documentation
5. Test thoroughly before submitting

### Test Function Template

```bash
test_new_functionality() {
    local description="Test new functionality"
    
    # Test implementation
    local response=$(curl -s [...])
    
    # Validation logic
    if [[ condition ]]; then
        log "DEBUG" "Test passed: $description"
        return 0
    else
        log "DEBUG" "Test failed: $description"
        return 1
    fi
}
```

### Reporting Issues

When reporting test issues, include:
- Test command used
- Error messages and logs
- System configuration
- Expected vs actual results
- Steps to reproduce

## Support and Resources

### Documentation References

- [Secman CLAUDE.md](CLAUDE.md) - Project overview and development commands
- [Translation Test Scenarios](test-translation-scenarios.md) - Comprehensive test case documentation
- [OpenRouter API Documentation](https://openrouter.ai/docs) - Translation service API reference

### Getting Help

1. Check the troubleshooting section in this guide
2. Review test logs and error messages
3. Test individual components manually
4. Consult project documentation
5. Report issues with detailed information

### Best Practices

- Run tests in isolated environments
- Use version control for test configurations
- Document custom test scenarios
- Regular test execution and monitoring
- Keep test data and configurations updated

---

**Note**: This testing framework is designed for comprehensive validation of the Secman translation feature. Regular execution of these tests helps ensure translation functionality remains robust and reliable across different deployment scenarios.