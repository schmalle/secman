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
 * Provides Secman entities as MCP resources
 */
@Singleton
public class ResourceProvider {
    
    private final JPAApi jpaApi;
    private final ObjectMapper objectMapper;
    
    @Inject
    public ResourceProvider(JPAApi jpaApi) {
        this.jpaApi = jpaApi;
        this.objectMapper = new ObjectMapper();
    }
    
    /**
     * List available resources
     */
    public JsonNode listResources(MCPSession session) throws MCPException {
        if (!session.isAuthenticated()) {
            throw new MCPException(MCPException.UNAUTHORIZED, "Authentication required");
        }
        
        ObjectNode result = objectMapper.createObjectNode();
        ArrayNode resources = objectMapper.createArrayNode();
        
        // Requirements resource
        ObjectNode requirementsResource = objectMapper.createObjectNode();
        requirementsResource.put("uri", "secman://requirements");
        requirementsResource.put("name", "Requirements");
        requirementsResource.put("description", "List of all requirements in the system");
        requirementsResource.put("mimeType", "application/json");
        resources.add(requirementsResource);
        
        // Standards resource
        ObjectNode standardsResource = objectMapper.createObjectNode();
        standardsResource.put("uri", "secman://standards");
        standardsResource.put("name", "Standards");
        standardsResource.put("description", "List of all standards in the system");
        standardsResource.put("mimeType", "application/json");
        resources.add(standardsResource);
        
        // Assets resource
        ObjectNode assetsResource = objectMapper.createObjectNode();
        assetsResource.put("uri", "secman://assets");
        assetsResource.put("name", "Assets");
        assetsResource.put("description", "List of all assets in the system");
        assetsResource.put("mimeType", "application/json");
        resources.add(assetsResource);
        
        // Risks resource
        ObjectNode risksResource = objectMapper.createObjectNode();
        risksResource.put("uri", "secman://risks");
        risksResource.put("name", "Risks");
        risksResource.put("description", "List of all risks in the system");
        risksResource.put("mimeType", "application/json");
        resources.add(risksResource);
        
        // Risk Assessments resource
        ObjectNode assessmentsResource = objectMapper.createObjectNode();
        assessmentsResource.put("uri", "secman://risk-assessments");
        assessmentsResource.put("name", "Risk Assessments");
        assessmentsResource.put("description", "List of all risk assessments in the system");
        assessmentsResource.put("mimeType", "application/json");
        resources.add(assessmentsResource);
        
        // Users resource (admin only)
        if (session.isAdmin()) {
            ObjectNode usersResource = objectMapper.createObjectNode();
            usersResource.put("uri", "secman://users");
            usersResource.put("name", "Users");
            usersResource.put("description", "List of all users in the system");
            usersResource.put("mimeType", "application/json");
            resources.add(usersResource);
        }
        
        result.set("resources", resources);
        return result;
    }
    
    /**
     * Read a specific resource
     */
    public JsonNode readResource(JsonNode params, MCPSession session) throws MCPException {
        if (!session.isAuthenticated()) {
            throw new MCPException(MCPException.UNAUTHORIZED, "Authentication required");
        }
        
        if (!params.has("uri")) {
            throw new MCPException(MCPException.INVALID_PARAMS, "Missing uri parameter");
        }
        
        String uri = params.get("uri").asText();
        
        return jpaApi.withTransaction(em -> {
            try {
                switch (uri) {
                    case "secman://requirements":
                        return readRequirements(em);
                    case "secman://standards":
                        return readStandards(em);
                    case "secman://assets":
                        return readAssets(em);
                    case "secman://risks":
                        return readRisks(em);
                    case "secman://risk-assessments":
                        return readRiskAssessments(em);
                    case "secman://users":
                        if (!session.isAdmin()) {
                            throw new MCPException(MCPException.FORBIDDEN, "Admin access required");
                        }
                        return readUsers(em);
                    default:
                        // Handle specific entity URIs like secman://requirements/123
                        return readSpecificResource(uri, em, session);
                }
            } catch (MCPException e) {
                throw new RuntimeException(e);
            } catch (Exception e) {
                throw new RuntimeException(new MCPException(MCPException.INTERNAL_ERROR, "Error reading resource", e.getMessage()));
            }
        });
    }
    
    private JsonNode readRequirements(jakarta.persistence.EntityManager em) {
        TypedQuery<Requirement> query = em.createQuery(
            "SELECT r FROM Requirement r ORDER BY r.id", Requirement.class);
        List<Requirement> requirements = query.getResultList();
        
        ObjectNode result = objectMapper.createObjectNode();
        ArrayNode contents = objectMapper.createArrayNode();
        
        ObjectNode content = objectMapper.createObjectNode();
        content.put("uri", "secman://requirements");
        content.put("mimeType", "application/json");
        content.set("text", Json.toJson(requirements));
        contents.add(content);
        
        result.set("contents", contents);
        return result;
    }
    
    private JsonNode readStandards(jakarta.persistence.EntityManager em) {
        TypedQuery<Standard> query = em.createQuery(
            "SELECT s FROM Standard s ORDER BY s.id", Standard.class);
        List<Standard> standards = query.getResultList();
        
        ObjectNode result = objectMapper.createObjectNode();
        ArrayNode contents = objectMapper.createArrayNode();
        
        ObjectNode content = objectMapper.createObjectNode();
        content.put("uri", "secman://standards");
        content.put("mimeType", "application/json");
        content.set("text", Json.toJson(standards));
        contents.add(content);
        
        result.set("contents", contents);
        return result;
    }
    
    private JsonNode readAssets(jakarta.persistence.EntityManager em) {
        TypedQuery<Asset> query = em.createQuery(
            "SELECT a FROM Asset a ORDER BY a.id", Asset.class);
        List<Asset> assets = query.getResultList();
        
        ObjectNode result = objectMapper.createObjectNode();
        ArrayNode contents = objectMapper.createArrayNode();
        
        ObjectNode content = objectMapper.createObjectNode();
        content.put("uri", "secman://assets");
        content.put("mimeType", "application/json");
        content.set("text", Json.toJson(assets));
        contents.add(content);
        
        result.set("contents", contents);
        return result;
    }
    
    private JsonNode readRisks(jakarta.persistence.EntityManager em) {
        TypedQuery<Risk> query = em.createQuery(
            "SELECT r FROM Risk r ORDER BY r.id", Risk.class);
        List<Risk> risks = query.getResultList();
        
        ObjectNode result = objectMapper.createObjectNode();
        ArrayNode contents = objectMapper.createArrayNode();
        
        ObjectNode content = objectMapper.createObjectNode();
        content.put("uri", "secman://risks");
        content.put("mimeType", "application/json");
        content.set("text", Json.toJson(risks));
        contents.add(content);
        
        result.set("contents", contents);
        return result;
    }
    
    private JsonNode readRiskAssessments(jakarta.persistence.EntityManager em) {
        TypedQuery<RiskAssessment> query = em.createQuery(
            "SELECT ra FROM RiskAssessment ra ORDER BY ra.id", RiskAssessment.class);
        List<RiskAssessment> assessments = query.getResultList();
        
        ObjectNode result = objectMapper.createObjectNode();
        ArrayNode contents = objectMapper.createArrayNode();
        
        ObjectNode content = objectMapper.createObjectNode();
        content.put("uri", "secman://risk-assessments");
        content.put("mimeType", "application/json");
        content.set("text", Json.toJson(assessments));
        contents.add(content);
        
        result.set("contents", contents);
        return result;
    }
    
    private JsonNode readUsers(jakarta.persistence.EntityManager em) {
        TypedQuery<User> query = em.createQuery(
            "SELECT u FROM User u ORDER BY u.id", User.class);
        List<User> users = query.getResultList();
        
        ObjectNode result = objectMapper.createObjectNode();
        ArrayNode contents = objectMapper.createArrayNode();
        
        ObjectNode content = objectMapper.createObjectNode();
        content.put("uri", "secman://users");
        content.put("mimeType", "application/json");
        content.set("text", Json.toJson(users));
        contents.add(content);
        
        result.set("contents", contents);
        return result;
    }
    
    private JsonNode readSpecificResource(String uri, jakarta.persistence.EntityManager em, MCPSession session) throws MCPException {
        // Parse URI like secman://requirements/123
        String[] parts = uri.split("/");
        if (parts.length < 4) {
            throw new MCPException(MCPException.RESOURCE_NOT_FOUND, "Invalid resource URI");
        }
        
        String entityType = parts[2];
        String entityId = parts[3];
        
        try {
            Long id = Long.parseLong(entityId);
            
            switch (entityType) {
                case "requirements":
                    return readSingleRequirement(em, id);
                case "standards":
                    return readSingleStandard(em, id);
                case "assets":
                    return readSingleAsset(em, id);
                case "risks":
                    return readSingleRisk(em, id);
                case "risk-assessments":
                    return readSingleRiskAssessment(em, id);
                case "users":
                    if (!session.isAdmin()) {
                        throw new MCPException(MCPException.FORBIDDEN, "Admin access required");
                    }
                    return readSingleUser(em, id);
                default:
                    throw new MCPException(MCPException.RESOURCE_NOT_FOUND, "Unknown entity type: " + entityType);
            }
        } catch (NumberFormatException e) {
            throw new MCPException(MCPException.INVALID_PARAMS, "Invalid entity ID: " + entityId);
        }
    }
    
    private JsonNode readSingleRequirement(jakarta.persistence.EntityManager em, Long id) throws MCPException {
        Requirement requirement = em.find(Requirement.class, id);
        if (requirement == null) {
            throw new MCPException(MCPException.RESOURCE_NOT_FOUND, "Requirement not found: " + id);
        }
        
        ObjectNode result = objectMapper.createObjectNode();
        ArrayNode contents = objectMapper.createArrayNode();
        
        ObjectNode content = objectMapper.createObjectNode();
        content.put("uri", "secman://requirements/" + id);
        content.put("mimeType", "application/json");
        content.set("text", Json.toJson(requirement));
        contents.add(content);
        
        result.set("contents", contents);
        return result;
    }
    
    private JsonNode readSingleStandard(jakarta.persistence.EntityManager em, Long id) throws MCPException {
        Standard standard = em.find(Standard.class, id);
        if (standard == null) {
            throw new MCPException(MCPException.RESOURCE_NOT_FOUND, "Standard not found: " + id);
        }
        
        ObjectNode result = objectMapper.createObjectNode();
        ArrayNode contents = objectMapper.createArrayNode();
        
        ObjectNode content = objectMapper.createObjectNode();
        content.put("uri", "secman://standards/" + id);
        content.put("mimeType", "application/json");
        content.set("text", Json.toJson(standard));
        contents.add(content);
        
        result.set("contents", contents);
        return result;
    }
    
    private JsonNode readSingleAsset(jakarta.persistence.EntityManager em, Long id) throws MCPException {
        Asset asset = em.find(Asset.class, id);
        if (asset == null) {
            throw new MCPException(MCPException.RESOURCE_NOT_FOUND, "Asset not found: " + id);
        }
        
        ObjectNode result = objectMapper.createObjectNode();
        ArrayNode contents = objectMapper.createArrayNode();
        
        ObjectNode content = objectMapper.createObjectNode();
        content.put("uri", "secman://assets/" + id);
        content.put("mimeType", "application/json");
        content.set("text", Json.toJson(asset));
        contents.add(content);
        
        result.set("contents", contents);
        return result;
    }
    
    private JsonNode readSingleRisk(jakarta.persistence.EntityManager em, Long id) throws MCPException {
        Risk risk = em.find(Risk.class, id);
        if (risk == null) {
            throw new MCPException(MCPException.RESOURCE_NOT_FOUND, "Risk not found: " + id);
        }
        
        ObjectNode result = objectMapper.createObjectNode();
        ArrayNode contents = objectMapper.createArrayNode();
        
        ObjectNode content = objectMapper.createObjectNode();
        content.put("uri", "secman://risks/" + id);
        content.put("mimeType", "application/json");
        content.set("text", Json.toJson(risk));
        contents.add(content);
        
        result.set("contents", contents);
        return result;
    }
    
    private JsonNode readSingleRiskAssessment(jakarta.persistence.EntityManager em, Long id) throws MCPException {
        RiskAssessment assessment = em.find(RiskAssessment.class, id);
        if (assessment == null) {
            throw new MCPException(MCPException.RESOURCE_NOT_FOUND, "Risk assessment not found: " + id);
        }
        
        ObjectNode result = objectMapper.createObjectNode();
        ArrayNode contents = objectMapper.createArrayNode();
        
        ObjectNode content = objectMapper.createObjectNode();
        content.put("uri", "secman://risk-assessments/" + id);
        content.put("mimeType", "application/json");
        content.set("text", Json.toJson(assessment));
        contents.add(content);
        
        result.set("contents", contents);
        return result;
    }
    
    private JsonNode readSingleUser(jakarta.persistence.EntityManager em, Long id) throws MCPException {
        User user = em.find(User.class, id);
        if (user == null) {
            throw new MCPException(MCPException.RESOURCE_NOT_FOUND, "User not found: " + id);
        }
        
        ObjectNode result = objectMapper.createObjectNode();
        ArrayNode contents = objectMapper.createArrayNode();
        
        ObjectNode content = objectMapper.createObjectNode();
        content.put("uri", "secman://users/" + id);
        content.put("mimeType", "application/json");
        content.set("text", Json.toJson(user));
        contents.add(content);
        
        result.set("contents", contents);
        return result;
    }
}