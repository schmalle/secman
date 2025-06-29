# Product Requirement Document (PRD)

## Title

Java Logic for XLSX File Upload and Parsing

---

## Document Version

Version: 1.1
Date: 2025-05-04
Author: schmalle

---

## Objective

The goal is to develop a Java-based backend logic that enables users to upload `.xlsx` files and parse the files for extracting data from a specific worksheet named **"Reqs"**. Each row in this worksheet represents a single requirement, and the data will be structured for further processing or storage.

---

## Scope

The system will consist of the following core functionalities:

1. **File Upload**: Enable users to upload `.xlsx` files through an API endpoint.
2. **File Validation**: Verify the uploaded file for type, size, and content structure.
3. **File Parsing**: Parse the **Reqs** worksheet to extract relevant data.
4. **Error Handling**: Handle errors gracefully, providing meaningful feedback to users.
5. **Extensibility**: Allow for easy integration of additional data extraction requirements.

---

## Functional Requirements

### 1. File Upload

- **Endpoint**: `/upload-xlsx`
- **HTTP Method**: `POST`
- **Input**: Multipart form data containing the `.xlsx` file.
- **Constraints**:
  - File size limit: 10MB.
  - Only `.xlsx` files are allowed.
- **Success Response**: Status `200` with a message indicating successful upload.
- **Failure Response**: Status `400` or `500` with an error message.

### 2. File Validation

- Validate the file type to ensure it’s `.xlsx`.
- Validate the file size to keep it within the defined limit.
- Check for empty or corrupted files and reject them.

### 3. File Parsing

- Use **Apache POI** to process `.xlsx` files.
- Locate and parse the sheet named **Reqs**:
  - If the sheet does not exist, return a meaningful error message (e.g., "Sheet 'Reqs' not found").
  - Validate the presence of the following column headers in the first row:
    - **Chapter**
    - **Norm**
    - **Short req**
    - **DetailsEN**
    - **MotivationEN**
    - **ExampleEN**
    - **UseCase**
  - If any required column is missing, return an error message (e.g., "Missing required column: Chapter").
- Parse each row starting from the second row (skipping the header):
  - Each row corresponds to a single **Requirement** instance (as defined in `models.Requirement.java`).
  - Extract values and map them to the `Requirement` fields:
    - **Short req** (Excel) -> `shortreq` (Requirement field)
    - **DetailsEN** (Excel) -> `details` (Requirement field)
    - **MotivationEN** (Excel) -> `motivation` (Requirement field)
    - **ExampleEN** (Excel) -> `example` (Requirement field)
    - Set `language` (Requirement field) to "EN" based on the column naming convention.
    - Extract **Chapter** (Excel) - *Note: No direct field in current `Requirement.java`.*
    - Extract **Norm** (Excel) - *Note: No direct field in current `Requirement.java`.*
  - Handle empty or missing values gracefully by assigning `null` or an appropriate default.
- Return the parsed data as a list of objects, where each object represents the data for one `Requirement`.

#### Sample Output (JSON reflecting Requirement structure)

```json
[
  {
    "shortreq": "User Authentication",
    "details": "System must allow users to log in using username and password.",
    "motivation": "Ensure only authorized users can access the system.",
    "example": "User enters credentials, system verifies against database.",
    "language": "EN",
    "chapter": "5.1", // Extracted, but not mapped to Requirement model
    "norm": "ISO-27001" // Extracted, but not mapped to Requirement model
  },
  {
    "shortreq": "Password Complexity",
    "details": "Passwords must meet complexity requirements (length, characters).",
    "motivation": "Enhance security against brute-force attacks.",
    "example": "Password must be >= 8 chars, include upper, lower, number, symbol.",
    "language": "EN",
    "chapter": "5.1.1", // Extracted, but not mapped to Requirement model
    "norm": "NIST-800-63b" // Extracted, but not mapped to Requirement model
  }
  // ... more requirements
]
```
