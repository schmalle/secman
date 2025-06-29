package controllers;

import models.Risk;
import models.Asset;
import play.db.jpa.JPAApi;
import play.libs.Json;
import play.mvc.*;

import javax.inject.Inject;
import jakarta.persistence.TypedQuery;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.util.List;

public class RiskController extends Controller {
    private final JPAApi jpaApi;

    @Inject
    public RiskController(JPAApi jpaApi) {
        this.jpaApi = jpaApi;
    }

    public Result list() {
        return jpaApi.withTransaction(em -> {
            TypedQuery<Risk> query = em.createQuery("SELECT r FROM Risk r ORDER BY r.createdAt DESC", Risk.class);
            List<Risk> risks = query.getResultList();
            return ok(Json.toJson(risks));
        });
    }

    public Result get(Long id) {
        return jpaApi.withTransaction(em -> {
            Risk risk = em.find(Risk.class, id);
            if (risk == null) {
                return notFound(Json.newObject().put("error", "Risk not found"));
            }
            return ok(Json.toJson(risk));
        });
    }

    public Result create(Http.Request request) {
        JsonNode json = request.body().asJson();
        if (json == null) {
            return badRequest(Json.newObject().put("error", "Expecting Json data"));
        }
        String name = json.findPath("name").asText();
        String description = json.findPath("description").asText();
        Integer likelihood = json.findPath("likelihood").asInt();
        Integer impact = json.findPath("impact").asInt();
        Long assetId = json.findPath("asset_id").asLong();
        String status = json.findPath("status").asText();
        String owner = json.findPath("owner").asText();
        String severity = json.findPath("severity").asText();
        String deadlineStr = json.findPath("deadline").asText();
        
        if (name == null || name.isBlank() || description == null || description.isBlank() || 
            likelihood == null || impact == null || assetId == null) {
            return badRequest(Json.newObject().put("error", "Missing required fields"));
        }
        return jpaApi.withTransaction(em -> {
            // Find the asset first
            Asset asset = em.find(Asset.class, assetId);
            if (asset == null) {
                return badRequest(Json.newObject().put("error", "Asset not found"));
            }
            
            Risk risk = new Risk();
            risk.setName(name);
            risk.setDescription(description);
            risk.setLikelihood(likelihood);
            risk.setImpact(impact);
            risk.setAsset(asset);
            
            if (status != null && !status.isBlank()) risk.setStatus(status);
            if (owner != null && !owner.isBlank()) risk.setOwner(owner);
            if (severity != null && !severity.isBlank()) risk.setSeverity(severity);
            if (deadlineStr != null && !deadlineStr.isBlank()) {
                try {
                    risk.setDeadline(LocalDate.parse(deadlineStr));
                } catch (Exception e) {
                    // Ignore invalid date formats
                }
            }
            
            em.persist(risk);
            em.flush();
            return created(Json.toJson(risk));
        });
    }

    public Result update(Long id, Http.Request request) {
        JsonNode json = request.body().asJson();
        if (json == null) {
            return badRequest(Json.newObject().put("error", "Expecting Json data"));
        }
        return jpaApi.withTransaction(em -> {
            Risk risk = em.find(Risk.class, id);
            if (risk == null) {
                return notFound(Json.newObject().put("error", "Risk not found"));
            }
            String name = json.findPath("name").asText();
            String description = json.findPath("description").asText();
            Integer likelihood = json.findPath("likelihood").asInt();
            Integer impact = json.findPath("impact").asInt();
            Long assetId = json.findPath("asset_id").asLong();
            String status = json.findPath("status").asText();
            String owner = json.findPath("owner").asText();
            String severity = json.findPath("severity").asText();
            String deadlineStr = json.findPath("deadline").asText();
            
            if (name != null && !name.isBlank()) risk.setName(name);
            if (description != null && !description.isBlank()) risk.setDescription(description);
            if (likelihood != null) risk.setLikelihood(likelihood);
            if (impact != null) risk.setImpact(impact);
            if (status != null && !status.isBlank()) risk.setStatus(status);
            if (owner != null && !owner.isBlank()) risk.setOwner(owner);
            if (severity != null && !severity.isBlank()) risk.setSeverity(severity);
            if (deadlineStr != null && !deadlineStr.isBlank()) {
                try {
                    risk.setDeadline(LocalDate.parse(deadlineStr));
                } catch (Exception e) {
                    // Ignore invalid date formats
                }
            }
            
            if (assetId != null) {
                Asset asset = em.find(Asset.class, assetId);
                if (asset == null) {
                    return badRequest(Json.newObject().put("error", "Asset not found"));
                }
                risk.setAsset(asset);
            }
            
            em.merge(risk);
            return ok(Json.toJson(risk));
        });
    }

    public Result delete(Long id) {
        return jpaApi.withTransaction(em -> {
            Risk risk = em.find(Risk.class, id);
            if (risk == null) {
                return notFound(Json.newObject().put("error", "Risk not found"));
            }
            em.remove(risk);
            return ok(Json.newObject().put("message", "Risk deleted successfully"));
        });
    }

    public Result getByAsset(Long assetId) {
        return jpaApi.withTransaction(em -> {
            // First check if the asset exists
            Asset asset = em.find(Asset.class, assetId);
            if (asset == null) {
                return notFound(Json.newObject().put("error", "Asset not found"));
            }
            
            TypedQuery<Risk> query = em.createQuery(
                "SELECT r FROM Risk r WHERE r.asset.id = :assetId ORDER BY r.createdAt DESC", 
                Risk.class);
            query.setParameter("assetId", assetId);
            List<Risk> risks = query.getResultList();
            return ok(Json.toJson(risks));
        });
    }
}
