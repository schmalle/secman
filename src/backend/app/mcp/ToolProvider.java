package mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import models.*;
import play.db.jpa.JPAApi;
import play.libs.Json;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.persistence.TypedQuery;
import java.util.List;

/**
 * Provides Secman operations as MCP tools
 */
@Singleton
public class ToolProvider {
    
    private final JPAApi jpaApi;
    private final ObjectMapper objectMapper;
    
    @Inject
    public ToolProvider(JPAApi jpaApi) {
        this.jpaApi = jpaApi;
        this.objectMapper = new ObjectMapper();
    }
    
    /**
     * List available tools
     */
    public JsonNode listTools(MCPSession session) throws MCPException {
        if (!session.isAuthenticated()) {
            throw new MCPException(MCPException.UNAUTHORIZED, "Authentication required");
        }
        
        ObjectNode result = objectMapper.createObjectNode();
        ArrayNode tools = objectMapper.createArrayNode();
        
        // Search Requirements tool
        ObjectNode searchRequirementsSchema = objectMapper.createObjectNode();
        searchRequirementsSchema.put("type", "object");
        
        ObjectNode searchProps = objectMapper.createObjectNode();
        ObjectNode queryProp = objectMapper.createObjectNode();
        queryProp.put("type", "string");
        queryProp.put("description", "Search query text");
        searchProps.set("query", queryProp);
        
        searchRequirementsSchema.set("properties", searchProps);
        
        ArrayNode searchRequired = objectMapper.createArrayNode();
        searchRequired.add("query");
        searchRequirementsSchema.set("required", searchRequired);
        
        ObjectNode searchRequirements = createToolDefinition(
            "search_requirements",
            "Search requirements by text or criteria",
            searchRequirementsSchema
        );
        tools.add(searchRequirements);
        
        // Create Requirement tool (admin only)
        if (session.isAdmin()) {
            ObjectNode createRequirementSchema = objectMapper.createObjectNode();
            createRequirementSchema.put("type", "object");
            
            ObjectNode createProps = objectMapper.createObjectNode();
            ObjectNode textProp = objectMapper.createObjectNode();
            textProp.put("type", "string");
            textProp.put("description", "Requirement text");
            createProps.set("text", textProp);
            
            ObjectNode categoryProp = objectMapper.createObjectNode();
            categoryProp.put("type", "string");
            categoryProp.put("description", "Requirement category");
            createProps.set("category", categoryProp);
            
            createRequirementSchema.set("properties", createProps);
            
            ArrayNode createRequired = objectMapper.createArrayNode();
            createRequired.add("text");
            createRequirementSchema.set("required", createRequired);
            
            ObjectNode createRequirement = createToolDefinition(
                "create_requirement",
                "Create a new requirement",
                createRequirementSchema
            );
            tools.add(createRequirement);
        }
        
        // Search Assets tool
        ObjectNode searchAssetsSchema = objectMapper.createObjectNode();
        searchAssetsSchema.put("type", "object");
        
        ObjectNode assetProps = objectMapper.createObjectNode();
        ObjectNode assetQueryProp = objectMapper.createObjectNode();
        assetQueryProp.put("type", "string");
        assetQueryProp.put("description", "Search query text");
        assetProps.set("query", assetQueryProp);
        
        searchAssetsSchema.set("properties", assetProps);
        
        ArrayNode assetRequired = objectMapper.createArrayNode();
        assetRequired.add("query");
        searchAssetsSchema.set("required", assetRequired);
        
        ObjectNode searchAssets = createToolDefinition(
            "search_assets",
            "Search assets by name or description",
            searchAssetsSchema
        );
        tools.add(searchAssets);
        
        // Get Risk Assessment tool
        ObjectNode riskAssessmentSchema = objectMapper.createObjectNode();
        riskAssessmentSchema.put("type", "object");
        
        ObjectNode riskProps = objectMapper.createObjectNode();
        ObjectNode assetIdProp = objectMapper.createObjectNode();
        assetIdProp.put("type", "number");
        assetIdProp.put("description", "Asset ID");
        riskProps.set("assetId", assetIdProp);
        
        riskAssessmentSchema.set("properties", riskProps);
        
        ArrayNode riskRequired = objectMapper.createArrayNode();
        riskRequired.add("assetId");
        riskAssessmentSchema.set("required", riskRequired);
        
        ObjectNode getRiskAssessment = createToolDefinition(
            "get_risk_assessment",
            "Get risk assessment for an asset",
            riskAssessmentSchema
        );
        tools.add(getRiskAssessment);
        
        result.set("tools", tools);
        return result;
    }
    
    /**
     * Call a specific tool
     */
    public JsonNode callTool(JsonNode params, MCPSession session) throws MCPException {
        if (!session.isAuthenticated()) {
            throw new MCPException(MCPException.UNAUTHORIZED, "Authentication required");
        }
        
        if (!params.has("name")) {
            throw new MCPException(MCPException.INVALID_PARAMS, "Missing tool name");
        }
        
        String toolName = params.get("name").asText();
        JsonNode arguments = params.get("arguments");
        
        return jpaApi.withTransaction(em -> {
            try {
                switch (toolName) {
                    case "search_requirements":
                        return searchRequirements(arguments, em);
                    case "create_requirement":
                        if (!session.isAdmin()) {
                            throw new MCPException(MCPException.FORBIDDEN, "Admin access required");
                        }
                        return createRequirement(arguments, em);
                    case "search_assets":
                        return searchAssets(arguments, em);
                    case "get_risk_assessment":
                        return getRiskAssessment(arguments, em);
                    default:
                        throw new MCPException(MCPException.METHOD_NOT_FOUND, "Unknown tool: " + toolName);
                }
            } catch (MCPException e) {
                throw new RuntimeException(e);
            } catch (Exception e) {
                throw new RuntimeException(new MCPException(MCPException.TOOL_ERROR, "Tool execution failed", e.getMessage()));
            }
        });
    }
    
    private JsonNode searchRequirements(JsonNode arguments, jakarta.persistence.EntityManager em) throws MCPException {
        if (!arguments.has("query")) {
            throw new MCPException(MCPException.INVALID_PARAMS, "Missing query parameter");
        }
        
        String query = arguments.get("query").asText();
        
        TypedQuery<Requirement> jpqlQuery = em.createQuery(
            "SELECT r FROM Requirement r WHERE LOWER(r.details) LIKE LOWER(:query) ORDER BY r.id", 
            Requirement.class);
        jpqlQuery.setParameter("query", "%" + query + "%");
        
        List<Requirement> requirements = jpqlQuery.getResultList();
        
        ObjectNode result = objectMapper.createObjectNode();
        ArrayNode content = objectMapper.createArrayNode();
        
        ObjectNode textContent = objectMapper.createObjectNode();
        textContent.put("type", "text");
        textContent.put("text", String.format("Found %d requirements matching '%s':\n\n%s", 
            requirements.size(), query, formatRequirements(requirements)));
        content.add(textContent);
        
        result.set("content", content);
        return result;
    }
    
    private JsonNode createRequirement(JsonNode arguments, jakarta.persistence.EntityManager em) throws MCPException {
        if (!arguments.has("text")) {
            throw new MCPException(MCPException.INVALID_PARAMS, "Missing required parameter: text");
        }
        
        String text = arguments.get("text").asText();
        String category = arguments.has("category") ? arguments.get("category").asText() : null;
        
        // Create the requirement
        Requirement requirement = new Requirement();
        requirement.setDetails(text);
        requirement.setShortreq(text.length() > 100 ? text.substring(0, 100) + "..." : text);
        if (category != null) {
            requirement.setChapter(category);
        }
        
        em.persist(requirement);
        em.flush();
        
        ObjectNode result = objectMapper.createObjectNode();
        ArrayNode content = objectMapper.createArrayNode();
        
        ObjectNode textContent = objectMapper.createObjectNode();
        textContent.put("type", "text");
        textContent.put("text", String.format("Successfully created requirement with ID %d:\n\nText: %s\nCategory: %s", 
            requirement.getId(), requirement.getDetails(), category != null ? category : "None"));
        content.add(textContent);
        
        result.set("content", content);
        return result;
    }
    
    private JsonNode searchAssets(JsonNode arguments, jakarta.persistence.EntityManager em) throws MCPException {
        if (!arguments.has("query")) {
            throw new MCPException(MCPException.INVALID_PARAMS, "Missing query parameter");
        }
        
        String query = arguments.get("query").asText();
        
        TypedQuery<Asset> jpqlQuery = em.createQuery(
            "SELECT a FROM Asset a WHERE LOWER(a.name) LIKE LOWER(:query) OR LOWER(a.description) LIKE LOWER(:query) ORDER BY a.id", 
            Asset.class);
        jpqlQuery.setParameter("query", "%" + query + "%");
        
        List<Asset> assets = jpqlQuery.getResultList();
        
        ObjectNode result = objectMapper.createObjectNode();
        ArrayNode content = objectMapper.createArrayNode();
        
        ObjectNode textContent = objectMapper.createObjectNode();
        textContent.put("type", "text");
        textContent.put("text", String.format("Found %d assets matching '%s':\n\n%s", 
            assets.size(), query, formatAssets(assets)));
        content.add(textContent);
        
        result.set("content", content);
        return result;
    }
    
    private JsonNode getRiskAssessment(JsonNode arguments, jakarta.persistence.EntityManager em) throws MCPException {
        if (!arguments.has("assetId")) {
            throw new MCPException(MCPException.INVALID_PARAMS, "Missing assetId parameter");
        }
        
        Long assetId = arguments.get("assetId").asLong();
        
        // Find the asset
        Asset asset = em.find(Asset.class, assetId);
        if (asset == null) {
            throw new MCPException(MCPException.INVALID_PARAMS, "Asset not found: " + assetId);
        }
        
        // Get risk assessments for this asset
        TypedQuery<RiskAssessment> query = em.createQuery(
            "SELECT ra FROM RiskAssessment ra WHERE ra.asset.id = :assetId ORDER BY ra.id", 
            RiskAssessment.class);
        query.setParameter("assetId", assetId);
        
        List<RiskAssessment> assessments = query.getResultList();
        
        ObjectNode result = objectMapper.createObjectNode();
        ArrayNode content = objectMapper.createArrayNode();
        
        ObjectNode textContent = objectMapper.createObjectNode();
        textContent.put("type", "text");
        textContent.put("text", String.format("Risk assessments for asset '%s' (ID: %d):\n\n%s", 
            asset.getName(), assetId, formatRiskAssessments(assessments)));
        content.add(textContent);
        
        result.set("content", content);
        return result;
    }
    
    private ObjectNode createToolDefinition(String name, String description, ObjectNode inputSchema) {
        ObjectNode tool = objectMapper.createObjectNode();
        tool.put("name", name);
        tool.put("description", description);
        tool.set("inputSchema", inputSchema);
        return tool;
    }
    
    private String formatRequirements(List<Requirement> requirements) {
        StringBuilder sb = new StringBuilder();
        for (Requirement req : requirements) {
            sb.append(String.format("%d. %s", req.getId(), req.getDetails() != null ? req.getDetails() : req.getShortreq()));
            if (req.getChapter() != null) {
                sb.append(String.format(" [%s]", req.getChapter()));
            }
            sb.append("\n\n");
        }
        return sb.toString();
    }
    
    private String formatAssets(List<Asset> assets) {
        StringBuilder sb = new StringBuilder();
        for (Asset asset : assets) {
            sb.append(String.format("%d. %s", asset.getId(), asset.getName()));
            if (asset.getDescription() != null) {
                sb.append(String.format("\n   Description: %s", asset.getDescription()));
            }
            sb.append("\n\n");
        }
        return sb.toString();
    }
    
    private String formatRiskAssessments(List<RiskAssessment> assessments) {
        if (assessments.isEmpty()) {
            return "No risk assessments found for this asset.";
        }
        
        StringBuilder sb = new StringBuilder();
        for (RiskAssessment assessment : assessments) {
            sb.append(String.format("Assessment ID: %d\n", assessment.getId()));
            sb.append("Assessment details available.\n\n");
        }
        return sb.toString();
    }
}