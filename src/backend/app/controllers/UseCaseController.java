package controllers;

import models.UseCase;
import play.db.jpa.JPAApi;
import play.libs.Json;
import play.mvc.*;

import com.fasterxml.jackson.databind.JsonNode; // Added for request body parsing
import jakarta.persistence.TypedQuery; // Added for JPA queries

import javax.inject.Inject;
import java.util.List;
import java.util.Optional;

import actions.Secured; // Import the Secured action

@With(Secured.class) // Apply the Secured action to the entire controller
public class UseCaseController extends Controller {

    private final JPAApi jpaApi;

    @Inject
    public UseCaseController(JPAApi jpaApi) {
        this.jpaApi = jpaApi;
    }

    // @Transactional(readOnly = true) // Replaced with withTransaction
    public Result getUseCases(Http.Request request) {
        return jpaApi.withTransaction(em -> {
            List<UseCase> useCases = em.createQuery("SELECT uc FROM UseCase uc ORDER BY uc.name", UseCase.class).getResultList();
            return ok(Json.toJson(useCases));
        });
    }

    // @Transactional(readOnly = true) // Replaced with withTransaction
    public Result getUseCase(Long id) {
        return jpaApi.withTransaction(em -> {
            Optional<UseCase> useCaseOptional = Optional.ofNullable(em.find(UseCase.class, id));
            return useCaseOptional
                    .map(useCase -> ok(Json.toJson(useCase)))
                    .orElse(notFound(Json.newObject().put("error", "UseCase not found")));
        });
    }

    // @Transactional // Replaced with withTransaction
    public Result createUseCase(Http.Request request) {
        JsonNode json = request.body().asJson();
        if (json == null) {
            return badRequest(Json.newObject().put("error", "Expecting Json data"));
        }
        try {
            UseCase useCase = Json.fromJson(json, UseCase.class);
            if (useCase.getName() == null || useCase.getName().trim().isEmpty()) {
                 return badRequest(Json.newObject().put("error", "Name is required"));
            }
            return jpaApi.withTransaction(em -> {
                // Check if use case with the same name already exists
                List<UseCase> existing = em
                    .createQuery("SELECT uc FROM UseCase uc WHERE LOWER(uc.name) = LOWER(:name)", UseCase.class)
                    .setParameter("name", useCase.getName().trim())
                    .getResultList();
                if (!existing.isEmpty()) {
                    return badRequest(Json.newObject().put("error", "UseCase with this name already exists"));
                }

                em.persist(useCase);
                return created(Json.toJson(useCase));
            });
        } catch (Exception e) {
            // Catching general exception for things like constraint violations if not handled above
            return badRequest(Json.newObject().put("error", "Error creating use case: " + e.getMessage()));
        }
    }

    // @Transactional // Replaced with withTransaction
    public Result updateUseCase(Http.Request request, Long id) {
        JsonNode json = request.body().asJson();
        if (json == null) {
            return badRequest(Json.newObject().put("error", "Expecting Json data"));
        }
        try {
            UseCase updatedUseCase = Json.fromJson(json, UseCase.class);
            if (updatedUseCase.getName() == null || updatedUseCase.getName().trim().isEmpty()) {
                 return badRequest(Json.newObject().put("error", "Name is required"));
            }

            return jpaApi.withTransaction(em -> {
                UseCase existingUseCase = em.find(UseCase.class, id);
                if (existingUseCase == null) {
                    return notFound(Json.newObject().put("error", "UseCase not found"));
                }

                // Check if another use case with the new name already exists (excluding the current one)
                List<UseCase> existingByName = em
                    .createQuery("SELECT uc FROM UseCase uc WHERE LOWER(uc.name) = LOWER(:name) AND uc.id != :id", UseCase.class)
                    .setParameter("name", updatedUseCase.getName().trim())
                    .setParameter("id", id)
                    .getResultList();
                if (!existingByName.isEmpty()) {
                    return badRequest(Json.newObject().put("error", "Another UseCase with this name already exists"));
                }

                existingUseCase.setName(updatedUseCase.getName().trim());
                // Potentially update other fields if the UseCase model grows
                em.merge(existingUseCase);
                return ok(Json.toJson(existingUseCase));
            });
        } catch (Exception e) {
            // Catching general exception for things like constraint violations if not handled above
            return badRequest(Json.newObject().put("error", "Error updating use case: " + e.getMessage()));
        }
    }

    // @Transactional // Replaced with withTransaction
    public Result deleteUseCase(Long id) {
        return jpaApi.withTransaction(em -> {
            UseCase useCase = em.find(UseCase.class, id);
            if (useCase == null) {
                return notFound(Json.newObject().put("error", "UseCase not found"));
            }
            try {
                // Check if this UseCase is associated with any Requirements
                TypedQuery<Long> query = em.createQuery(
                    "SELECT COUNT(r) FROM Requirement r JOIN r.usecases uc WHERE uc.id = :useCaseId", Long.class);
                query.setParameter("useCaseId", id);
                Long count = query.getSingleResult();

                if (count > 0) {
                    return badRequest(Json.newObject().put("error", "UseCase is associated with " + count + " requirement(s) and cannot be deleted."));
                }

                em.remove(useCase);
                return ok(Json.newObject().put("message", "UseCase deleted successfully"));
            } catch (Exception e) {
                // Catching general exception for things like constraint violations if not handled above
                return internalServerError(Json.newObject().put("error", "Error deleting use case: " + e.getMessage()));
            }
        });
    }
}
