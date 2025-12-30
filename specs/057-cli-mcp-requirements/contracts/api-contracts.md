# API Contracts: CLI and MCP Requirements Management

**Feature**: 057-cli-mcp-requirements
**Date**: 2025-12-29

## Overview

This feature reuses existing backend REST endpoints. No new API development required.

---

## Existing REST Endpoints (Used by CLI)

### Export Requirements to Excel

```http
GET /api/requirements/export/xlsx
Authorization: Bearer <token>
```

**Response**:
- Status: 200 OK
- Content-Type: `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`
- Body: Binary Excel file

**Error Responses**:
- 401 Unauthorized - Invalid or missing token
- 403 Forbidden - Insufficient role permissions

---

### Export Requirements to Word

```http
GET /api/requirements/export/docx
Authorization: Bearer <token>
```

**Response**:
- Status: 200 OK
- Content-Type: `application/vnd.openxmlformats-officedocument.wordprocessingml.document`
- Body: Binary Word file

**Error Responses**:
- 401 Unauthorized
- 403 Forbidden

---

### Create Requirement

```http
POST /api/requirements
Authorization: Bearer <token>
Content-Type: application/json

{
  "shortreq": "string (required)",
  "details": "string (optional)",
  "motivation": "string (optional)",
  "example": "string (optional)",
  "norm": "string (optional)",
  "usecase": "string (optional)",
  "chapter": "string (optional)"
}
```

**Response**:
- Status: 201 Created
- Body: Created Requirement entity

**Error Responses**:
- 400 Bad Request - Validation error
- 401 Unauthorized
- 403 Forbidden

---

### Delete All Requirements

```http
DELETE /api/requirements/all
Authorization: Bearer <token>
```

**Response**:
- Status: 200 OK
- Body: `{ "deletedCount": number }`

**Error Responses**:
- 401 Unauthorized
- 403 Forbidden - Requires ADMIN role

---

## New MCP Tool Contracts

### export_requirements

**Tool Name**: `export_requirements`
**Operation**: READ
**Permission**: REQUIREMENTS_READ

**Input Schema**:
```json
{
  "type": "object",
  "properties": {
    "format": {
      "type": "string",
      "enum": ["xlsx", "docx"],
      "description": "Export format: xlsx for Excel, docx for Word"
    }
  },
  "required": ["format"]
}
```

**Success Response**:
```json
{
  "content": {
    "data": "<base64-encoded-file-content>",
    "filename": "requirements_export_20251229.xlsx",
    "format": "xlsx",
    "contentType": "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "requirementCount": 150,
    "fileSizeBytes": 45678
  }
}
```

**Error Response**:
```json
{
  "code": "EXECUTION_ERROR",
  "message": "Failed to generate export: <details>"
}
```

---

### add_requirement

**Tool Name**: `add_requirement`
**Operation**: WRITE
**Permission**: REQUIREMENTS_WRITE

**Input Schema**:
```json
{
  "type": "object",
  "properties": {
    "shortreq": {
      "type": "string",
      "description": "Short requirement text (required)"
    },
    "details": {
      "type": "string",
      "description": "Detailed description"
    },
    "motivation": {
      "type": "string",
      "description": "Why this requirement exists"
    },
    "example": {
      "type": "string",
      "description": "Implementation example"
    },
    "norm": {
      "type": "string",
      "description": "Regulatory norm reference"
    },
    "usecase": {
      "type": "string",
      "description": "Use case description"
    },
    "chapter": {
      "type": "string",
      "description": "Chapter/category for grouping"
    }
  },
  "required": ["shortreq"]
}
```

**Success Response**:
```json
{
  "content": {
    "success": true,
    "id": 123,
    "message": "Requirement created successfully",
    "operation": "CREATED"
  }
}
```

**Error Responses**:
```json
{
  "code": "VALIDATION_ERROR",
  "message": "Short requirement text is required"
}
```

---

### delete_all_requirements

**Tool Name**: `delete_all_requirements`
**Operation**: DELETE
**Permission**: REQUIREMENTS_WRITE + ADMIN role

**Input Schema**:
```json
{
  "type": "object",
  "properties": {
    "confirm": {
      "type": "boolean",
      "description": "Must be true to confirm deletion"
    }
  },
  "required": ["confirm"]
}
```

**Success Response**:
```json
{
  "content": {
    "success": true,
    "deletedCount": 150,
    "message": "Deleted 150 requirements"
  }
}
```

**Error Responses**:
```json
{
  "code": "UNAUTHORIZED",
  "message": "ADMIN role required for delete operation"
}
```
```json
{
  "code": "CONFIRMATION_REQUIRED",
  "message": "Delete operation requires confirm: true"
}
```

---

## CLI Command Contracts

### export-requirements

```bash
secman export-requirements --format <xlsx|docx> [--output <path>] \
  --username <user> --password <pass> [--backend-url <url>] [--verbose]
```

**Parameters**:
| Parameter | Required | Default | Description |
|-----------|----------|---------|-------------|
| --format | Yes | - | Export format: xlsx or docx |
| --output | No | ./requirements_export_YYYYMMDD.{ext} | Output file path |
| --username | Yes* | SECMAN_USERNAME env | Backend username |
| --password | Yes* | SECMAN_PASSWORD env | Backend password |
| --backend-url | No | http://localhost:8080 | Backend API URL |
| --verbose | No | false | Enable verbose output |

**Exit Codes**:
- 0: Success
- 1: Error (auth, network, validation)

---

### add-requirement

```bash
secman add-requirement --shortreq <text> [--chapter <name>] [--details <text>] \
  [--motivation <text>] [--example <text>] [--norm <name>] [--usecase <name>] \
  --username <user> --password <pass> [--backend-url <url>] [--verbose]
```

**Parameters**:
| Parameter | Required | Default | Description |
|-----------|----------|---------|-------------|
| --shortreq | Yes | - | Short requirement text |
| --chapter | No | - | Chapter/category |
| --details | No | - | Detailed description |
| --motivation | No | - | Motivation text |
| --example | No | - | Example text |
| --norm | No | - | Norm reference |
| --usecase | No | - | Use case |
| --username | Yes* | SECMAN_USERNAME env | Backend username |
| --password | Yes* | SECMAN_PASSWORD env | Backend password |
| --backend-url | No | http://localhost:8080 | Backend API URL |
| --verbose | No | false | Enable verbose output |

**Exit Codes**:
- 0: Success
- 1: Error

---

### delete-all-requirements

```bash
secman delete-all-requirements --confirm \
  --username <user> --password <pass> [--backend-url <url>] [--verbose]
```

**Parameters**:
| Parameter | Required | Default | Description |
|-----------|----------|---------|-------------|
| --confirm | Yes | - | Required safety flag |
| --username | Yes* | SECMAN_USERNAME env | Backend username (ADMIN) |
| --password | Yes* | SECMAN_PASSWORD env | Backend password |
| --backend-url | No | http://localhost:8080 | Backend API URL |
| --verbose | No | false | Enable verbose output |

**Exit Codes**:
- 0: Success
- 1: Error (auth, authorization, network)
