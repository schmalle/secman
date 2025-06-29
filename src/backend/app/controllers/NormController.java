package controllers;

import com.fasterxml.jackson.databind.JsonNode;
import models.Norm;
import play.db.jpa.JPAApi;
import play.libs.Json;
import play.mvc.*;
import play.mvc.Results;

import jakarta.inject.Inject;
import jakarta.persistence.TypedQuery;
import java.util.List;
import java.util.Optional;

public class NormController extends Controller {

    private final JPAApi jpaApi;

    @Inject
    public NormController(JPAApi jpaApi) {
        this.jpaApi = jpaApi;
    }

    public Result list() {
        return jpaApi.withTransaction(em -> {
            try {
                TypedQuery<Norm> query = em.createQuery("SELECT n FROM Norm n ORDER BY n.name", Norm.class);
                List<Norm> norms = query.getResultList();
                return Results.ok(Json.toJson(norms));
            } catch (Exception e) {
                play.Logger.error("Error listing norms", e);
                return Results.internalServerError(Json.newObject().put("error", "Could not retrieve norms"));
            }
        });
    }

    public Result create(Http.Request request) {
        JsonNode json = request.body().asJson();
        if (json == null) {
            return Results.badRequest(Json.newObject().put("error", "Expecting Json data"));
        }

        String name = json.findPath("name").textValue();
        String version = json.findPath("version").textValue();
        Integer year = json.findPath("year").asInt();

        if (name == null || name.trim().isEmpty()) {
            return Results.badRequest(Json.newObject().put("error", "Name is required"));
        }

        return jpaApi.withTransaction(em -> {
            try {
                // Check if norm with same name already exists
                TypedQuery<Long> countQuery = em.createQuery(
                    "SELECT COUNT(n) FROM Norm n WHERE n.name = :name", Long.class);
                countQuery.setParameter("name", name);
                Long count = countQuery.getSingleResult();
                
                if (count > 0) {
                    return Results.badRequest(Json.newObject().put("error", "Norm with this name already exists"));
                }

                Norm norm = new Norm();
                norm.setName(name);
                norm.setVersion(version);
                if (year > 0) {
                    norm.setYear(year);
                }

                em.persist(norm);
                em.flush(); // Force the insert to get the generated ID

                return Results.created(Json.toJson(norm));
            } catch (Exception e) {
                play.Logger.error("Error creating norm", e);
                return Results.internalServerError(Json.newObject().put("error", "Could not create norm"));
            }
        });
    }

    public Result update(Long id, Http.Request request) {
        JsonNode json = request.body().asJson();
        if (json == null) {
            return Results.badRequest(Json.newObject().put("error", "Expecting Json data"));
        }

        return jpaApi.withTransaction(em -> {
            try {
                Norm norm = em.find(Norm.class, id);
                if (norm == null) {
                    return Results.notFound(Json.newObject().put("error", "Norm not found"));
                }

                // Update fields if provided
                String name = json.findPath("name").textValue();
                if (name != null && !name.trim().isEmpty()) {
                    // Check if another norm with this name exists
                    TypedQuery<Long> countQuery = em.createQuery(
                        "SELECT COUNT(n) FROM Norm n WHERE n.name = :name AND n.id != :id", Long.class);
                    countQuery.setParameter("name", name);
                    countQuery.setParameter("id", id);
                    Long count = countQuery.getSingleResult();
                    
                    if (count > 0) {
                        return Results.badRequest(Json.newObject().put("error", "Norm with this name already exists"));
                    }
                    norm.setName(name);
                }

                String version = json.findPath("version").textValue();
                if (version != null) {
                    norm.setVersion(version);
                }

                if (json.has("year")) {
                    Integer year = json.findPath("year").asInt();
                    if (year > 0) {
                        norm.setYear(year);
                    } else {
                        norm.setYear(null);
                    }
                }

                // Update timestamp will be handled by @PreUpdate
                norm = em.merge(norm);

                return Results.ok(Json.toJson(norm));
            } catch (Exception e) {
                play.Logger.error("Error updating norm with id: " + id, e);
                return Results.internalServerError(Json.newObject().put("error", "Could not update norm"));
            }
        });
    }

    public Result delete(Long id) {
        return jpaApi.withTransaction(em -> {
            try {
                Norm norm = em.find(Norm.class, id);
                if (norm == null) {
                    return Results.notFound(Json.newObject().put("error", "Norm not found"));
                }

                em.remove(norm);
                return Results.ok(Json.newObject().put("message", "Norm deleted successfully"));
            } catch (Exception e) {
                play.Logger.error("Error deleting norm with id: " + id, e);
                return Results.internalServerError(Json.newObject().put("error", "Could not delete norm"));
            }
        });
    }

    public Result get(Long id) {
        return jpaApi.withTransaction(em -> {
            try {
                Norm norm = em.find(Norm.class, id);
                if (norm == null) {
                    return Results.notFound(Json.newObject().put("error", "Norm not found"));
                }

                return Results.ok(Json.toJson(norm));
            } catch (Exception e) {
                play.Logger.error("Error getting norm with id: " + id, e);
                return Results.internalServerError(Json.newObject().put("error", "Could not retrieve norm"));
            }
        });
    }

    public Result deleteAll() {
        return jpaApi.withTransaction(em -> {
            try {
                // Get count of norms before deletion for logging
                TypedQuery<Long> countQuery = em.createQuery("SELECT COUNT(n) FROM Norm n", Long.class);
                Long normCount = countQuery.getSingleResult();
                
                if (normCount == 0) {
                    return Results.ok(Json.newObject()
                        .put("message", "No norms to delete")
                        .put("deletedCount", 0));
                }

                // Delete all norms (cascade deletion will handle relationships)
                int deletedCount = em.createQuery("DELETE FROM Norm n").executeUpdate();
                
                play.Logger.info("Deleted {} norms via delete all operation", deletedCount);
                
                return Results.ok(Json.newObject()
                    .put("message", "All norms deleted successfully")
                    .put("deletedCount", deletedCount));
                    
            } catch (Exception e) {
                play.Logger.error("Error deleting all norms", e);
                return Results.internalServerError(Json.newObject()
                    .put("error", "Could not delete all norms")
                    .put("details", e.getMessage()));
            }
        });
    }
}