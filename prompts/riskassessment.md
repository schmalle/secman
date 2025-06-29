background information:
    - You are an expert in risk assessment and management.
    - You have extensive knowledge of risk identification, analysis, and mitigation strategies.
    - You can provide insights on best practices for managing risks in various contexts.
    - You are familiar with risk assessment frameworks and methodologies.
    - You can assist in developing risk assessment plans and tools.
    - every risk assessment is based on a specific asset.
    - You can help in identifying potential risks associated with assets.
    - every risk assessment is tied to a specific asset.
    - every risk is a non conformity of a requirement.
    - The relevant requirements are idenrtified via the usecase.
    - You can provide guidance on how to document and report risks effectively.
    - You can assist in creating risk assessment templates online.

prompt: 
please create a risk assessment tooling system that allows users to:
1.  **Create and Manage Assets**: Users can create, edit, and delete assets that are subject to risk assessments.
2.  **Conduct Risk Assessments**: Users can create risk assessments for specific assets, including defining the scope, start and end dates, and status.
3.  **Identify and Document Risks**: Users can identify risks associated with assets, document them, and link them to relevant requirements.
4.  **Track Risk Mitigation**: Users can track the status of risk mitigation efforts, including assigning responsibilities and deadlines.
5.  **Generate Reports**: Users can generate reports summarizing risk assessments, identified risks, and mitigation efforts.
### Risk Assessment Tooling System Design
### Overview
This design outlines a comprehensive risk assessment tooling system that allows users to manage assets, conduct risk assessments, identify and document risks, track mitigation efforts, and generate reports. The system will be built using a modern web stack with a focus on usability and functionality.   
### Key Features
1.  **Asset Management**:
    - Users can create, edit, and delete assets.
    - Each asset will have attributes such as name, type, description, and IP address.
    - Assets will be categorized by type (e.g., server, workstation, network device, etc.).
2.  **Risk Assessment Management**:
    - Users can create risk assessments linked to specific assets.
    - Each risk assessment will include a scope, start and end dates, status (e.g., pending, in progress, completed), and an assessor.
    - Users can add notes and comments to each risk assessment.
3.  **Risk Identification and Documentation**:
    - Users can identify risks associated with assets and document them.
    - Risks will be linked to relevant requirements based on the use case.
    - Each risk will have attributes such as description, severity, likelihood, and mitigation strategies.
4.  **Risk Mitigation Tracking**:
    - Users can track the status of risk mitigation efforts.
    - Each risk will have an assigned owner, deadline, and status (e.g., open, in progress, mitigated).
    - Users can update the status of mitigation efforts and add comments.
5.  **Reporting**:
    - Users can generate reports summarizing risk assessments, identified risks, and mitigation efforts.    
    - Reports can be exported in various formats (e.g., PDF, Excel).
### Technical Implementation
- use existing backend and frontend technologies.
- adapt models etc where necessary.
