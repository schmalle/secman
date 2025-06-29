package controllers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import models.Asset;
import play.db.jpa.JPAApi;
import play.libs.Json;
import play.mvc.Controller;
import play.mvc.Http;
import play.mvc.Result;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import java.util.List;
import java.util.Optional;

@Singleton
public class AssetController extends Controller {

    private final JPAApi jpaApi;
    private final ObjectMapper objectMapper;

    @Inject
    public AssetController(JPAApi jpaApi) {
        this.jpaApi = jpaApi;
        this.objectMapper = new ObjectMapper();
    }

    public Result list() {
        return jpaApi.withTransaction(em -> {
            TypedQuery<Asset> query = em.createQuery("SELECT a FROM Asset a ORDER BY a.createdAt DESC", Asset.class);
            List<Asset> assets = query.getResultList();
            return ok(Json.toJson(assets));
        });
    }

    public Result get(Long id) {
        return jpaApi.withTransaction(em -> {
            Asset asset = em.find(Asset.class, id);
            if (asset == null) {
                return notFound(Json.toJson("Asset not found"));
            }
            return ok(Json.toJson(asset));
        });
    }

    public Result create(Http.Request request) {
        JsonNode json = request.body().asJson();
        if (json == null) {
            return badRequest(Json.toJson("Invalid JSON"));
        }
        
        try {
            Asset asset = objectMapper.treeToValue(json, Asset.class);
            
            return jpaApi.withTransaction(em -> {
                em.persist(asset);
                return created(Json.toJson(asset));
            });
        } catch (Exception e) {
            return badRequest(Json.toJson("Invalid asset data: " + e.getMessage()));
        }
    }

    public Result update(Long id, Http.Request request) {
        JsonNode json = request.body().asJson();
        if (json == null) {
            return badRequest(Json.toJson("Invalid JSON"));
        }

        return jpaApi.withTransaction(em -> {
            Asset asset = em.find(Asset.class, id);
            if (asset == null) {
                return notFound(Json.toJson("Asset not found"));
            }

            try {
                if (json.has("name")) asset.setName(json.get("name").asText());
                if (json.has("type")) asset.setType(json.get("type").asText());
                if (json.has("ip")) asset.setIp(json.get("ip").asText());
                if (json.has("owner")) asset.setOwner(json.get("owner").asText());
                if (json.has("description")) asset.setDescription(json.get("description").asText());

                em.merge(asset);
                return ok(Json.toJson(asset));
            } catch (Exception e) {
                return badRequest(Json.toJson("Invalid asset data: " + e.getMessage()));
            }
        });
    }

    public Result delete(Long id) {
        return jpaApi.withTransaction(em -> {
            Asset asset = em.find(Asset.class, id);
            if (asset == null) {
                return notFound(Json.toJson("Asset not found"));
            }

            em.remove(asset);
            return ok(Json.toJson("Asset deleted successfully"));
        });
    }
}
