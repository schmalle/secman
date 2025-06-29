# Translation Feature Test Scenarios

## Overview
This document outlines comprehensive test scenarios for the Secman translation feature, which enables multilingual document generation using AI-powered translation services via OpenRouter API.

## Test Categories

### 1. Translation Configuration Management Tests

#### 1.1 Configuration CRUD Operations
- **TC-001**: Create new translation configuration with valid OpenRouter API key
- **TC-002**: Retrieve all translation configurations
- **TC-003**: Get active translation configuration
- **TC-004**: Update translation configuration (API key, model, parameters)
- **TC-005**: Delete translation configuration
- **TC-006**: Activate/deactivate translation configuration

#### 1.2 Configuration Validation Tests
- **TC-007**: Test configuration with invalid API key
- **TC-008**: Test configuration with invalid model name
- **TC-009**: Test configuration with invalid base URL
- **TC-010**: Test configuration with out-of-range parameters (max_tokens, temperature)
- **TC-011**: Test configuration activation when multiple configs exist
- **TC-012**: Test configuration deletion when it's the active one

### 2. Translation Service Tests

#### 2.1 Basic Translation Tests
- **TC-013**: Translate simple text to German
- **TC-014**: Translate simple text to English
- **TC-015**: Translate complex text with technical terms
- **TC-016**: Translate text with special characters and formatting
- **TC-017**: Translate empty/null text (edge case)
- **TC-018**: Translate very long text (boundary testing)

#### 2.2 Language Support Tests
- **TC-019**: Test supported language codes ("de", "german", "en", "english")
- **TC-020**: Test unsupported language codes
- **TC-021**: Test case-insensitive language detection
- **TC-022**: Test language code validation

#### 2.3 Error Handling Tests
- **TC-023**: Translation with no active configuration
- **TC-024**: Translation with invalid API credentials
- **TC-025**: Translation with network connectivity issues
- **TC-026**: Translation with API rate limiting
- **TC-027**: Translation service timeout handling
- **TC-028**: Fallback to original text on translation failure

### 3. Requirements Translation Tests

#### 3.1 Individual Field Translation Tests
- **TC-029**: Translate requirement `shortreq` field
- **TC-030**: Translate requirement `details` field
- **TC-031**: Translate requirement `example` field
- **TC-032**: Translate requirement `motivation` field
- **TC-033**: Translate requirement `usecase` field
- **TC-034**: Translate requirement `norm` field
- **TC-035**: Translate requirement `chapter` field

#### 3.2 Bulk Translation Tests
- **TC-036**: Translate all requirements to German
- **TC-037**: Translate all requirements to English
- **TC-038**: Translate requirements with mixed languages
- **TC-039**: Translate large dataset (100+ requirements)
- **TC-040**: Translate requirements with missing fields
- **TC-041**: Partial translation failure handling

### 4. Document Export with Translation Tests

#### 4.1 Full Export Tests
- **TC-042**: Export all requirements as DOCX with German translation
- **TC-043**: Export all requirements as DOCX with English translation
- **TC-044**: Export with translation when no requirements exist
- **TC-045**: Export with translation authentication test

#### 4.2 Use Case Specific Export Tests
- **TC-046**: Export requirements by use case ID with German translation
- **TC-047**: Export requirements by use case ID with English translation
- **TC-048**: Export with invalid use case ID
- **TC-049**: Export use case with no associated requirements

#### 4.3 Document Content Validation Tests
- **TC-050**: Verify translated content accuracy in exported document
- **TC-051**: Verify document structure and formatting preservation
- **TC-052**: Verify headers and labels translation
- **TC-053**: Verify special characters handling in document
- **TC-054**: Verify document metadata and properties

### 5. API Endpoint Integration Tests

#### 5.1 Translation Configuration API Tests
- **TC-055**: GET /api/translation-config (list all)
- **TC-056**: POST /api/translation-config (create new)
- **TC-057**: GET /api/translation-config/active (get active)
- **TC-058**: PUT /api/translation-config/:id (update)
- **TC-059**: DELETE /api/translation-config/:id (delete)
- **TC-060**: POST /api/translation-config/:id/test (test config)

#### 5.2 Translation Export API Tests
- **TC-061**: GET /api/requirements/export/docx/translated/:language
- **TC-062**: GET /api/requirements/export/docx/usecase/:id/translated/:language
- **TC-063**: API authentication and authorization tests
- **TC-064**: API rate limiting tests
- **TC-065**: API error response validation

### 6. Performance and Load Tests

#### 6.1 Performance Tests
- **TC-066**: Translation performance with single requirement
- **TC-067**: Translation performance with 10 requirements
- **TC-068**: Translation performance with 100+ requirements
- **TC-069**: Document export performance with translation
- **TC-070**: Concurrent translation requests handling

#### 6.2 Resource Usage Tests
- **TC-071**: Memory usage during bulk translation
- **TC-072**: CPU usage during translation operations
- **TC-073**: Network bandwidth usage
- **TC-074**: Database connection handling during translations

### 7. Security and Access Control Tests

#### 7.1 Authentication Tests
- **TC-075**: Translation operations without authentication
- **TC-076**: Translation operations with invalid session
- **TC-077**: Translation operations with expired session
- **TC-078**: Role-based access control (normaluser vs adminuser)

#### 7.2 API Key Security Tests
- **TC-079**: API key storage encryption validation
- **TC-080**: API key transmission security
- **TC-081**: API key masking in UI/logs
- **TC-082**: API key rotation testing

#### 7.3 Input Validation Tests
- **TC-083**: SQL injection prevention in translation inputs
- **TC-084**: XSS prevention in translated content
- **TC-085**: Input sanitization for special characters
- **TC-086**: Maximum input length validation

### 8. Integration and End-to-End Tests

#### 8.1 Workflow Tests
- **TC-087**: Complete workflow: Setup → Translate → Export
- **TC-088**: Multi-user concurrent translation operations
- **TC-089**: Translation after requirement updates
- **TC-090**: Translation consistency across multiple exports

#### 8.2 System Integration Tests
- **TC-091**: Translation with database failures
- **TC-092**: Translation with external API failures
- **TC-093**: Translation during system maintenance
- **TC-094**: Translation with different browser/client types

### 9. Data Consistency and Recovery Tests

#### 9.1 Data Integrity Tests
- **TC-095**: Translation data consistency after system restart
- **TC-096**: Translation configuration backup and restore
- **TC-097**: Partial translation recovery after interruption
- **TC-098**: Translation audit trail validation

#### 9.2 Disaster Recovery Tests
- **TC-099**: Translation functionality after database recovery
- **TC-100**: Translation service recovery after external API outage

## Test Environment Requirements

### Prerequisites
- Secman backend running on port 9000
- Secman frontend running on port 4321
- MariaDB database with test data
- Valid OpenRouter API key for testing
- Internet connectivity for external API calls

### Test Data Requirements
- Sample requirements with all translatable fields populated
- Multiple use cases with associated requirements
- Test users with different roles (normaluser, adminuser)
- Various translation configurations for testing

### Expected Outputs
- Detailed test execution logs
- Translation accuracy reports
- Performance metrics
- Error handling validation
- Security test results
- Document export samples

## Test Execution Priority

### High Priority (Critical Path)
- Translation configuration management (TC-001 to TC-012)
- Basic translation functionality (TC-013 to TC-028)
- Document export with translation (TC-042 to TC-054)

### Medium Priority (Feature Completeness)
- Requirements translation (TC-029 to TC-041)
- API endpoint integration (TC-055 to TC-065)
- Security and access control (TC-075 to TC-086)

### Low Priority (Quality Assurance)
- Performance and load tests (TC-066 to TC-074)
- Integration and end-to-end tests (TC-087 to TC-100)

## Success Criteria

### Functional Success
- All translation operations complete successfully
- Translated content maintains semantic accuracy
- Document exports generate correctly formatted files
- Error handling gracefully manages failures

### Performance Success
- Translation of single requirement: < 5 seconds
- Translation of 100 requirements: < 300 seconds
- Document export with translation: < 30 seconds
- System remains responsive during operations

### Security Success
- All API endpoints properly authenticated
- API keys securely stored and transmitted
- Input validation prevents malicious content
- Role-based access controls enforced