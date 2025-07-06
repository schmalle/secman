# Prompts Directory Documentation

## Overview

The `prompts` directory contains a collection of technical specification documents, implementation guides, and design instructions for the secman (Security Management) system. These files serve as detailed prompts and specifications for implementing various features related to requirements management, risk assessment, asset management, and system integrations.

## Table of Contents

- [File Categories](#file-categories)
- [Implementation Guides](#implementation-guides)
- [Model Requirements](#model-requirements)
- [Format Specifications](#format-specifications)
- [System Design Documents](#system-design-documents)
- [File Details](#file-details)

## File Categories

The prompt files can be categorized into four main types:

### 🏗️ Implementation Guides
Documents providing step-by-step implementation instructions for complex features.

### 📋 Model Requirements 
Specifications for database models, entities, and their relationships.

### 📄 Format Specifications
Detailed formatting requirements for exports and document generation.

### 🎯 System Design Documents
High-level design documents and architectural specifications.

## Implementation Guides

### riskass2.md - Risk Assessment Response System Implementation
**Category:** Implementation Guide  
**Status:** Complete specification  

A comprehensive implementation guide for building a Risk Assessment Response System with the following key components:

- **Email Notification System**: Automated email sending to respondents with secure tokens
- **Respondent Interface**: Token-based access for external users to complete assessments
- **Response Management**: Backend tracking and management of assessment responses
- **Security Framework**: Token generation, validation, and access control
- **Database Integration**: Response tracking and data persistence

**Key Features Specified:**
- Email templates for notifications, reminders, and completion summaries
- Frontend components for response interface and email configuration
- Backend models for response tracking and email management
- API endpoints for assessment distribution and response collection

### addnorm.md - Norm Management Enhancement
**Category:** Implementation Guide  
**Status:** Detailed specification  

Instructions for enhancing the norm management functionality with focus on:

- **Excel Import Improvements**: Support for multiple norms per cell (semicolon-separated)
- **Many-to-Many Relationships**: Between Requirements and Norms entities
- **Case-Insensitive Lookups**: While preserving original casing for display
- **UI Enhancements**: CRUD operations for norms and multi-select associations

**Technical Requirements:**
- Database evolution scripts for join tables
- Controller modifications for norm processing
- Frontend components for norm management and association

### parseusecases.md - UseCase Model Implementation
**Category:** Implementation Guide  
**Status:** Basic specification  

Brief instructions for implementing UseCase functionality:

- **Model Creation**: Play Framework UseCase model
- **Database Evolution**: Suitable evolution scripts
- **UI Development**: Following existing frontend design patterns
- **Excel Integration**: Processing comma-separated use cases during import
- **Requirement Association**: Checkbox-based UI for linking use cases to requirements

## Model Requirements

### risk.md - Risk Entity Specification
**Category:** Model Requirements  
**Status:** Complete specification  

Detailed specification for a Risk model in Play Framework with:

**Entity Fields:**
- `id`: Auto-generated primary key
- `name`: Required string (max 255 characters)
- `description`: Required string (max 1024 characters)
- `likelihood`: Integer (1-5 scale)
- `impact`: Integer (1-5 scale)
- `riskLevel`: Computed field based on likelihood × impact matrix
- `createdAt`/`updatedAt`: Automatic timestamps

**Technical Requirements:**
- JPA/Hibernate annotations
- Bean Validation for input validation
- Lifecycle methods for automatic computation
- Database evolution script with both Up and Down migrations
- UI consistent with existing Norm Management interface

### system.md - Asset and Risk Assessment Models
**Category:** Model Requirements  
**Status:** Implementation Complete (✅)  

Documentation of completed implementation including:

**Asset Model:**
- Entity with id, name, type, IP address, owner, timestamps
- Predefined asset types (Server, Workstation, Network Device, etc.)
- Full CRUD operations and validation

**Risk Assessment Model:**
- Links to specific assets
- Status management (PENDING, IN_PROGRESS, COMPLETED, CANCELLED)
- Date range tracking with validation
- Assessor and requestor assignment

**Completed Features:**
- Database evolution scripts
- Backend controllers with full CRUD API
- Frontend React components with Bootstrap styling
- Navigation integration
- Responsive design implementation

## Format Specifications

### chapter.md - Requirements Export Format
**Category:** Format Specification  
**Status:** Complete specification  

Detailed specification for exporting requirements to Microsoft Word (`.docx`) format:

**Document Structure:**
1. **Title Page**: "Requirements Export" with generation date
2. **Table of Contents**: Dynamically generated with chapter navigation
3. **Requirements by Chapter**: Organized by norm-derived chapters

**Formatting Requirements:**
- Chapter headings derived from Requirement.norm field
- Individual requirement sections with ID and short description
- Norm references, detailed descriptions, and optional motivation/examples
- Horizontal rules between requirements for clarity
- Standard Word heading styles for proper ToC generation

**Technical Notes:**
- Backend implementation using Apache POI for Word document generation
- Consistent formatting with clean, readable fonts (Calibri, Arial)
- Professional document structure suitable for review and reference

## System Design Documents

### riskassessment.md - Risk Assessment Tooling System
**Category:** System Design  
**Status:** High-level design  

Design outline for a comprehensive risk assessment tooling system:

**Core Capabilities:**
1. **Asset Management**: Create, edit, delete assets with attributes (name, type, description, IP)
2. **Risk Assessment Management**: Asset-linked assessments with scope, dates, and status
3. **Risk Documentation**: Risk identification and requirement linkage
4. **Mitigation Tracking**: Status tracking with ownership and deadlines  
5. **Reporting**: Summary reports with export capabilities (PDF, Excel)

**Technical Approach:**
- Modern web stack utilizing existing backend and frontend technologies
- Model adaptations where necessary
- Focus on usability and functionality

### riskassessmentdesign.md - Modern Risk Assessment System
**Category:** System Design  
**Status:** Requirements specification  

Requirements for a modern, pragmatic risk assessment system with:

**Key Requirements:**
- Asset creation and management capabilities
- Risk assessment creation, distribution, and completion via UI
- Email-based assessment distribution with secure links
- Requirement-based risk identification (non-conformity model)
- Respondent access via secure tokens
- Yes/No/N/A response model for requirements

**System Characteristics:**
- User-friendly and secure design
- Efficient asset and assessment management
- Comprehensive information capture and reporting
- Test coverage for both UI and backend components

### systemandrisk.md
**Category:** Unknown  
**Status:** Empty file  

This file appears to be a placeholder or incomplete document containing only a single character ("1.").

## File Details

| File | Size | Primary Focus | Implementation Status |
|------|------|---------------|---------------------|
| `addnorm.md` | ~2.6KB | Norm management enhancement | Specification complete |
| `chapter.md` | ~2.1KB | Word export formatting | Specification complete |
| `parseusecases.md` | ~0.3KB | UseCase model creation | Basic specification |
| `risk.md` | ~2.4KB | Risk entity specification | Specification complete |
| `riskass2.md` | ~7.8KB | Response system implementation | Complete guide |
| `riskassessment.md` | ~1.8KB | System design overview | High-level design |
| `riskassessmentdesign.md` | ~1.1KB | Modern system requirements | Requirements complete |
| `system.md` | ~3.2KB | Asset/Risk models | ✅ Implementation complete |
| `systemandrisk.md` | ~0.01KB | Unknown | Empty/incomplete |

## Usage Guidelines

These prompt files serve as:

1. **Implementation Blueprints**: Detailed technical specifications for developers
2. **Feature Requirements**: Business and functional requirements for new capabilities  
3. **Integration Guides**: Instructions for connecting different system components
4. **Quality Standards**: Formatting and design consistency requirements

## Related Documentation

- **Main README**: `/README.md` - Project overview and setup instructions
- **Test Documentation**: `/docs/` - Testing guides and strategies
- **Memory Files**: `/.serena/memories/` - Development workflow and completion checklists

---

*This documentation was generated to provide a comprehensive overview of the prompts directory content and structure within the secman project.*