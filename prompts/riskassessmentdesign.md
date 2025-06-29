Have a look at the current code base.
as developer of a super modern and pragmatic risk assessment tooling system, you are tasked with designing a comprehensive risk assessment system that meets the following requirements:
risk assessment system should be able to:
1.  **Create and Manage Assets**: Users can create, edit, and delete assets that are subject to risk assessments.
2.  **Conduct Risk Assessments**: Users can create risk assessments for specific assets, including defining the scope, start and end dates, and status.
3.  **Identify and Document Risks**: Users can identify risks associated with assets, document them, and link them to relevant requirements.
4.  **Track Risk Mitigation**: Users can track the status of risk mitigation efforts, including assigning responsibilities and deadlines.
5.  **Generate Reports**: Users can generate reports summarizing risk assessments, identified risks, and mitigation efforts.

The risk assessments shall be sent out via email.
The risk assessments shall be startable via the UI
The risk assessments shall be completable via the UI

Please create also test code for both UI and backend.

Risk assessment logic shall be like follows:
A risk assessment is based on a specific asset and a dedicated use case, which defines the requirements.
Every risk is a non-conformity of a requirement.
The person filling out the the risk assessment is called the respondent.
The respondent shall be able to fill out the risk assessment via a secure link.
The risk assessment system should be designed to be user-friendly, secure, and efficient, allowing for easy management of assets and risk assessments while ensuring that all necessary information is captured and reported effectively. The following sections outline the design and implementation details for the system.
Every requirement can be answered with Yes, No or N/A.
 