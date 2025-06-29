package mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import jakarta.inject.Singleton;

/**
 * Provides MCP prompts for common Secman workflows
 */
@Singleton
public class PromptProvider {
    
    private final ObjectMapper objectMapper;
    
    public PromptProvider() {
        this.objectMapper = new ObjectMapper();
    }
    
    /**
     * List available prompts
     */
    public JsonNode listPrompts(MCPSession session) throws MCPException {
        if (!session.isAuthenticated()) {
            throw new MCPException(MCPException.UNAUTHORIZED, "Authentication required");
        }
        
        ObjectNode result = objectMapper.createObjectNode();
        ArrayNode prompts = objectMapper.createArrayNode();
        
        // Create requirement with compliance mapping
        ObjectNode createRequirementPrompt = createPromptDefinition(
            "create_requirement_with_compliance",
            "Create a new requirement with compliance mapping",
            "Help create a new security requirement with proper compliance mapping to relevant standards",
            objectMapper.createArrayNode()
                .add(createPromptArgument("standard_name", "Name of the compliance standard (e.g., ISO 27001, NIST)", true))
                .add(createPromptArgument("requirement_text", "The requirement text to create", false))
        );
        prompts.add(createRequirementPrompt);
        
        // Risk assessment workflow
        ObjectNode riskAssessmentPrompt = createPromptDefinition(
            "assess_asset_risk",
            "Assess risk for a specific asset", 
            "Guide through the process of assessing risks for a specific asset in the system",
            objectMapper.createArrayNode()
                .add(createPromptArgument("asset_name", "Name of the asset to assess", true))
                .add(createPromptArgument("risk_type", "Type of risk to assess (e.g., security, operational)", false))
        );
        prompts.add(riskAssessmentPrompt);
        
        // Compliance report generation
        ObjectNode complianceReportPrompt = createPromptDefinition(
            "generate_compliance_report",
            "Generate compliance report for standard",
            "Generate a comprehensive compliance report for a specific standard showing coverage and gaps",
            objectMapper.createArrayNode()
                .add(createPromptArgument("standard_name", "Name of the compliance standard", true))
                .add(createPromptArgument("report_type", "Type of report (summary, detailed, gaps-only)", false))
        );
        prompts.add(complianceReportPrompt);
        
        // Security requirements review
        ObjectNode securityReviewPrompt = createPromptDefinition(
            "review_security_requirements",
            "Review security requirements for completeness",
            "Review existing security requirements to identify gaps and ensure comprehensive coverage",
            objectMapper.createArrayNode()
                .add(createPromptArgument("scope", "Scope of review (all, by-standard, by-category)", false))
        );
        prompts.add(securityReviewPrompt);
        
        // Asset inventory analysis
        ObjectNode assetAnalysisPrompt = createPromptDefinition(
            "analyze_asset_inventory",
            "Analyze asset inventory and risk coverage",
            "Analyze the current asset inventory and identify assets that lack proper risk assessments",
            objectMapper.createArrayNode()
        );
        prompts.add(assetAnalysisPrompt);
        
        result.set("prompts", prompts);
        return result;
    }
    
    /**
     * Get a specific prompt
     */
    public JsonNode getPrompt(JsonNode params, MCPSession session) throws MCPException {
        if (!session.isAuthenticated()) {
            throw new MCPException(MCPException.UNAUTHORIZED, "Authentication required");
        }
        
        if (!params.has("name")) {
            throw new MCPException(MCPException.INVALID_PARAMS, "Missing prompt name");
        }
        
        String promptName = params.get("name").asText();
        JsonNode arguments = params.get("arguments");
        
        switch (promptName) {
            case "create_requirement_with_compliance":
                return getCreateRequirementPrompt(arguments);
            case "assess_asset_risk":
                return getAssessAssetRiskPrompt(arguments);
            case "generate_compliance_report":
                return getGenerateComplianceReportPrompt(arguments);
            case "review_security_requirements":
                return getReviewSecurityRequirementsPrompt(arguments);
            case "analyze_asset_inventory":
                return getAnalyzeAssetInventoryPrompt(arguments);
            default:
                throw new MCPException(MCPException.METHOD_NOT_FOUND, "Unknown prompt: " + promptName);
        }
    }
    
    private JsonNode getCreateRequirementPrompt(JsonNode arguments) {
        String standardName = arguments != null && arguments.has("standard_name") ? 
            arguments.get("standard_name").asText() : "[STANDARD_NAME]";
        String requirementText = arguments != null && arguments.has("requirement_text") ? 
            arguments.get("requirement_text").asText() : "";
        
        StringBuilder prompt = new StringBuilder();
        prompt.append("# Create Security Requirement with Compliance Mapping\n\n");
        prompt.append(String.format("I need to create a new security requirement for the %s standard. ", standardName));
        prompt.append("Please help me with the following:\n\n");
        
        if (requirementText.isEmpty()) {
            prompt.append("## Step 1: Define the Requirement\n");
            prompt.append("What specific security requirement do you want to create? Please provide:\n");
            prompt.append("- Clear, actionable requirement text\n");
            prompt.append("- Specific compliance category if applicable\n\n");
        } else {
            prompt.append(String.format("## Requirement Text:\n%s\n\n", requirementText));
        }
        
        prompt.append("## Step 2: Compliance Mapping\n");
        prompt.append(String.format("Please help map this requirement to specific %s controls or sections.\n\n", standardName));
        
        prompt.append("## Step 3: Implementation Guidance\n");
        prompt.append("Provide implementation guidance including:\n");
        prompt.append("- Who should be responsible for this requirement\n");
        prompt.append("- How compliance can be measured or verified\n");
        prompt.append("- Any related requirements or dependencies\n\n");
        
        prompt.append("Use the Secman MCP tools to:\n");
        prompt.append("1. Search for existing similar requirements\n");
        prompt.append("2. Create the new requirement once defined\n");
        prompt.append("3. Generate a compliance report to verify coverage\n");
        
        return createPromptResponse(prompt.toString());
    }
    
    private JsonNode getAssessAssetRiskPrompt(JsonNode arguments) {
        String assetName = arguments != null && arguments.has("asset_name") ? 
            arguments.get("asset_name").asText() : "[ASSET_NAME]";
        String riskType = arguments != null && arguments.has("risk_type") ? 
            arguments.get("risk_type").asText() : "security";
        
        StringBuilder prompt = new StringBuilder();
        prompt.append("# Risk Assessment Workflow\n\n");
        prompt.append(String.format("I need to assess %s risks for the asset: %s\n\n", riskType, assetName));
        
        prompt.append("## Step 1: Asset Information\n");
        prompt.append("First, let me gather information about this asset:\n");
        prompt.append("- Search for the asset in the system\n");
        prompt.append("- Review existing risk assessments\n");
        prompt.append("- Identify asset criticality and dependencies\n\n");
        
        prompt.append("## Step 2: Risk Identification\n");
        prompt.append(String.format("For %s risks, consider:\n", riskType));
        if ("security".equals(riskType)) {
            prompt.append("- Confidentiality threats\n");
            prompt.append("- Integrity risks\n");
            prompt.append("- Availability concerns\n");
            prompt.append("- Access control vulnerabilities\n");
        } else {
            prompt.append("- Operational failures\n");
            prompt.append("- Process disruptions\n");
            prompt.append("- Resource availability\n");
            prompt.append("- Performance issues\n");
        }
        prompt.append("\n");
        
        prompt.append("## Step 3: Risk Analysis\n");
        prompt.append("For each identified risk:\n");
        prompt.append("- Assess likelihood (Low/Medium/High)\n");
        prompt.append("- Evaluate impact (Low/Medium/High)\n");
        prompt.append("- Calculate risk score\n");
        prompt.append("- Identify existing controls\n\n");
        
        prompt.append("## Step 4: Risk Treatment\n");
        prompt.append("Recommend risk treatment options:\n");
        prompt.append("- Accept: Document acceptable risks\n");
        prompt.append("- Mitigate: Implement additional controls\n");
        prompt.append("- Transfer: Insurance or third-party handling\n");
        prompt.append("- Avoid: Eliminate the risk source\n\n");
        
        prompt.append("Use the Secman MCP tools to:\n");
        prompt.append("1. Search for the asset\n");
        prompt.append("2. Get existing risk assessments\n");
        prompt.append("3. Review related requirements\n");
        
        return createPromptResponse(prompt.toString());
    }
    
    private JsonNode getGenerateComplianceReportPrompt(JsonNode arguments) {
        String standardName = arguments != null && arguments.has("standard_name") ? 
            arguments.get("standard_name").asText() : "[STANDARD_NAME]";
        String reportType = arguments != null && arguments.has("report_type") ? 
            arguments.get("report_type").asText() : "summary";
        
        StringBuilder prompt = new StringBuilder();
        prompt.append("# Compliance Report Generation\n\n");
        prompt.append(String.format("I need to generate a %s compliance report for %s.\n\n", reportType, standardName));
        
        prompt.append("## Report Scope\n");
        prompt.append("This report will include:\n");
        
        switch (reportType) {
            case "detailed":
                prompt.append("- Complete list of all requirements\n");
                prompt.append("- Implementation status for each requirement\n");
                prompt.append("- Evidence and documentation references\n");
                prompt.append("- Responsible parties and timelines\n");
                break;
            case "gaps-only":
                prompt.append("- Identified compliance gaps\n");
                prompt.append("- Missing or incomplete requirements\n");
                prompt.append("- Recommendations for remediation\n");
                prompt.append("- Priority ranking of gaps\n");
                break;
            default: // summary
                prompt.append("- Overall compliance status\n");
                prompt.append("- Key metrics and statistics\n");
                prompt.append("- High-level gap analysis\n");
                prompt.append("- Executive summary\n");
        }
        prompt.append("\n");
        
        prompt.append("## Report Generation Process\n");
        prompt.append("1. **Data Collection**: Gather all relevant requirements and assessments\n");
        prompt.append("2. **Analysis**: Evaluate compliance status and identify gaps\n");
        prompt.append("3. **Formatting**: Structure the report according to standard formats\n");
        prompt.append("4. **Review**: Validate findings and recommendations\n\n");
        
        prompt.append("## Key Metrics to Include\n");
        prompt.append("- Total number of requirements\n");
        prompt.append("- Compliance percentage\n");
        prompt.append("- Number of assessed assets\n");
        prompt.append("- Risk coverage statistics\n");
        prompt.append("- Outstanding action items\n\n");
        
        prompt.append("Use the Secman MCP tools to:\n");
        prompt.append("1. Generate the compliance report\n");
        prompt.append("2. Search for specific requirements\n");
        prompt.append("3. Get asset and risk assessment data\n");
        
        return createPromptResponse(prompt.toString());
    }
    
    private JsonNode getReviewSecurityRequirementsPrompt(JsonNode arguments) {
        String scope = arguments != null && arguments.has("scope") ? 
            arguments.get("scope").asText() : "all";
        
        StringBuilder prompt = new StringBuilder();
        prompt.append("# Security Requirements Review\n\n");
        prompt.append(String.format("I need to review security requirements with scope: %s\n\n", scope));
        
        prompt.append("## Review Objectives\n");
        prompt.append("This review will assess:\n");
        prompt.append("- Completeness of security requirements coverage\n");
        prompt.append("- Clarity and actionability of requirement statements\n");
        prompt.append("- Alignment with current threat landscape\n");
        prompt.append("- Compliance with applicable standards\n");
        prompt.append("- Implementation feasibility\n\n");
        
        prompt.append("## Review Methodology\n");
        prompt.append("### Phase 1: Requirements Inventory\n");
        prompt.append("- Collect all security requirements\n");
        prompt.append("- Categorize by domain (access control, encryption, monitoring, etc.)\n");
        prompt.append("- Map to relevant standards and frameworks\n\n");
        
        prompt.append("### Phase 2: Gap Analysis\n");
        prompt.append("- Compare against industry best practices\n");
        prompt.append("- Identify missing security domains\n");
        prompt.append("- Check for outdated or obsolete requirements\n\n");
        
        prompt.append("### Phase 3: Quality Assessment\n");
        prompt.append("- Evaluate requirement clarity and specificity\n");
        prompt.append("- Check for measurable acceptance criteria\n");
        prompt.append("- Assess implementation guidance adequacy\n\n");
        
        prompt.append("## Review Checklist\n");
        prompt.append("□ Access Control & Authentication\n");
        prompt.append("□ Data Protection & Encryption\n");
        prompt.append("□ Network Security\n");
        prompt.append("□ Application Security\n");
        prompt.append("□ Monitoring & Logging\n");
        prompt.append("□ Incident Response\n");
        prompt.append("□ Business Continuity\n");
        prompt.append("□ Vendor Management\n");
        prompt.append("□ Training & Awareness\n");
        prompt.append("□ Physical Security\n\n");
        
        prompt.append("Use the Secman MCP tools to:\n");
        prompt.append("1. Search requirements by category\n");
        prompt.append("2. Generate compliance reports\n");
        prompt.append("3. Review asset coverage\n");
        
        return createPromptResponse(prompt.toString());
    }
    
    private JsonNode getAnalyzeAssetInventoryPrompt(JsonNode arguments) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("# Asset Inventory Analysis\n\n");
        prompt.append("I need to analyze the current asset inventory and assess risk coverage.\n\n");
        
        prompt.append("## Analysis Objectives\n");
        prompt.append("This analysis will:\n");
        prompt.append("- Review completeness of asset inventory\n");
        prompt.append("- Identify assets lacking risk assessments\n");
        prompt.append("- Evaluate asset categorization and criticality\n");
        prompt.append("- Assess security control coverage\n");
        prompt.append("- Recommend improvements to asset management\n\n");
        
        prompt.append("## Asset Analysis Framework\n");
        prompt.append("### Asset Classification\n");
        prompt.append("- **Critical**: Business-critical systems and data\n");
        prompt.append("- **Important**: Systems supporting key business functions\n");
        prompt.append("- **Standard**: Regular business systems\n");
        prompt.append("- **Low Impact**: Non-critical supporting systems\n\n");
        
        prompt.append("### Risk Assessment Coverage\n");
        prompt.append("For each asset, verify:\n");
        prompt.append("- Current risk assessments exist\n");
        prompt.append("- Assessments are up-to-date (within 12 months)\n");
        prompt.append("- All relevant risk types are covered\n");
        prompt.append("- Risk treatment plans are documented\n\n");
        
        prompt.append("## Key Metrics to Calculate\n");
        prompt.append("- Total number of assets\n");
        prompt.append("- Assets by classification level\n");
        prompt.append("- Percentage with current risk assessments\n");
        prompt.append("- Average risk score by asset type\n");
        prompt.append("- Number of untreated high-risk findings\n\n");
        
        prompt.append("## Deliverables\n");
        prompt.append("1. **Asset Inventory Report**: Complete list with classifications\n");
        prompt.append("2. **Risk Coverage Matrix**: Assets vs. risk assessment status\n");
        prompt.append("3. **Gap Analysis**: Assets requiring attention\n");
        prompt.append("4. **Action Plan**: Prioritized recommendations\n\n");
        
        prompt.append("Use the Secman MCP tools to:\n");
        prompt.append("1. Search and list all assets\n");
        prompt.append("2. Get risk assessments for each asset\n");
        prompt.append("3. Generate summary reports\n");
        
        return createPromptResponse(prompt.toString());
    }
    
    private ObjectNode createPromptDefinition(String name, String description, String hint, ArrayNode arguments) {
        ObjectNode prompt = objectMapper.createObjectNode();
        prompt.put("name", name);
        prompt.put("description", description);
        if (hint != null) {
            prompt.put("hint", hint);
        }
        if (arguments != null) {
            prompt.set("arguments", arguments);
        }
        return prompt;
    }
    
    private ObjectNode createPromptArgument(String name, String description, boolean required) {
        ObjectNode arg = objectMapper.createObjectNode();
        arg.put("name", name);
        arg.put("description", description);
        arg.put("required", required);
        return arg;
    }
    
    private JsonNode createPromptResponse(String promptText) {
        ObjectNode result = objectMapper.createObjectNode();
        ArrayNode messages = objectMapper.createArrayNode();
        
        ObjectNode message = objectMapper.createObjectNode();
        ObjectNode role = objectMapper.createObjectNode();
        role.put("type", "user");
        message.set("role", role);
        
        ObjectNode content = objectMapper.createObjectNode();
        content.put("type", "text");
        content.put("text", promptText);
        
        ArrayNode contentArray = objectMapper.createArrayNode();
        contentArray.add(content);
        message.set("content", contentArray);
        
        messages.add(message);
        result.set("messages", messages);
        
        return result;
    }
}