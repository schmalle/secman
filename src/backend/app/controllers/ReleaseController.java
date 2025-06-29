package controllers;

import actions.Secured;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import models.Release;
import models.ReleaseStatus;
import models.User;
import play.db.jpa.JPAApi;
import play.libs.Json;
import play.mvc.Controller;
import play.mvc.Http;
import play.mvc.Result;
import play.mvc.With;
import services.ReleaseService;

import jakarta.inject.Inject;
import jakarta.persistence.NoResultException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;

@With(Secured.class)
public class ReleaseController extends Controller {

    private final ReleaseService releaseService;
    private final ObjectMapper objectMapper;
    private final JPAApi jpaApi;

    @Inject
    public ReleaseController(ReleaseService releaseService, ObjectMapper objectMapper, JPAApi jpaApi) {
        this.releaseService = releaseService;
        this.objectMapper = objectMapper;
        this.jpaApi = jpaApi;
    }

    // GET /api/releases - Get all releases
    public Result getAllReleases() {
        try {
            List<Release> releases = releaseService.getAllReleases();
            return ok(Json.toJson(releases));
        } catch (Exception e) {
            return internalServerError(Json.newObject()
                .put("error", "Failed to retrieve releases: " + e.getMessage()));
        }
    }

    // GET /api/releases/current - Get current active release
    public Result getCurrentRelease() {
        try {
            Release currentRelease = releaseService.getCurrentActiveRelease();
            if (currentRelease == null) {
                return notFound(Json.newObject()
                    .put("error", "No active release found"));
            }
            return ok(Json.toJson(currentRelease));
        } catch (Exception e) {
            return internalServerError(Json.newObject()
                .put("error", "Failed to retrieve current release: " + e.getMessage()));
        }
    }

    // GET /api/releases/stats - Get release statistics
    public Result getReleaseStats() {
        try {
            ObjectNode stats = Json.newObject();
            Release currentRelease = releaseService.getCurrentActiveRelease();
            
            stats.put("currentRelease", currentRelease != null ? Json.toJson(currentRelease) : Json.newObject());
            stats.put("draftCount", releaseService.getDraftReleasesCount());
            stats.put("totalReleases", releaseService.getTotalReleasesCount());
            stats.put("activeAssessments", releaseService.getActiveAssessmentsCount());
            
            return ok(stats);
        } catch (Exception e) {
            return internalServerError(Json.newObject()
                .put("error", "Failed to retrieve release statistics: " + e.getMessage()));
        }
    }

    // GET /api/releases/:id - Get release by ID
    public Result getReleaseById(Long id) {
        try {
            Release release = releaseService.getReleaseById(id);
            if (release == null) {
                return notFound(Json.newObject()
                    .put("error", "Release not found with id: " + id));
            }
            return ok(Json.toJson(release));
        } catch (Exception e) {
            return internalServerError(Json.newObject()
                .put("error", "Failed to retrieve release: " + e.getMessage()));
        }
    }

    // GET /api/releases/version/:version - Get release by version
    public Result getReleaseByVersion(String version) {
        try {
            Release release = releaseService.getReleaseByVersion(version);
            if (release == null) {
                return notFound(Json.newObject()
                    .put("error", "Release not found with version: " + version));
            }
            return ok(Json.toJson(release));
        } catch (Exception e) {
            return internalServerError(Json.newObject()
                .put("error", "Failed to retrieve release: " + e.getMessage()));
        }
    }

    // GET /api/releases/status/:status - Get releases by status
    public Result getReleasesByStatus(String statusStr) {
        try {
            ReleaseStatus status = ReleaseStatus.valueOf(statusStr.toUpperCase());
            List<Release> releases = releaseService.getReleasesByStatus(status);
            return ok(Json.toJson(releases));
        } catch (IllegalArgumentException e) {
            return badRequest(Json.newObject()
                .put("error", "Invalid status: " + statusStr + ". Valid values are: DRAFT, ACTIVE, ARCHIVED"));
        } catch (Exception e) {
            return internalServerError(Json.newObject()
                .put("error", "Failed to retrieve releases: " + e.getMessage()));
        }
    }

    // POST /api/releases - Create new release
    public Result createRelease(Http.Request request) {
        JsonNode json = request.body().asJson();
        if (json == null) {
            return badRequest(Json.newObject()
                .put("error", "Expected JSON data"));
        }

        try {
            // Validate required fields
            if (!json.has("version") || json.get("version").asText().trim().isEmpty()) {
                return badRequest(Json.newObject()
                    .put("error", "Version is required"));
            }
            if (!json.has("name") || json.get("name").asText().trim().isEmpty()) {
                return badRequest(Json.newObject()
                    .put("error", "Name is required"));
            }

            String version = json.get("version").asText().trim();
            String name = json.get("name").asText().trim();
            String description = json.has("description") ? json.get("description").asText("") : "";

            // Validate version format (semantic versioning)
            if (!version.matches("^\\d+\\.\\d+\\.\\d+$")) {
                return badRequest(Json.newObject()
                    .put("error", "Version must follow semantic versioning format (e.g., 1.0.0)"));
            }

            // Get current user (should be admin)
            User currentUser = getCurrentUser(request);
            if (currentUser == null || !currentUser.hasRole("ADMIN")) {
                return forbidden(Json.newObject()
                    .put("error", "Admin access required"));
            }

            Release release = releaseService.createRelease(version, name, description, currentUser);
            return created(Json.toJson(release));

        } catch (IllegalArgumentException e) {
            return badRequest(Json.newObject()
                .put("error", e.getMessage()));
        } catch (Exception e) {
            return internalServerError(Json.newObject()
                .put("error", "Failed to create release: " + e.getMessage()));
        }
    }

    // PUT /api/releases/:id - Update release
    public Result updateRelease(Http.Request request, Long id) {
        JsonNode json = request.body().asJson();
        if (json == null) {
            return badRequest(Json.newObject()
                .put("error", "Expected JSON data"));
        }

        try {
            // Get current user (should be admin)
            User currentUser = getCurrentUser(request);
            if (currentUser == null || !currentUser.hasRole("ADMIN")) {
                return forbidden(Json.newObject()
                    .put("error", "Admin access required"));
            }

            // Validate required fields
            if (!json.has("version") || json.get("version").asText().trim().isEmpty()) {
                return badRequest(Json.newObject()
                    .put("error", "Version is required"));
            }
            if (!json.has("name") || json.get("name").asText().trim().isEmpty()) {
                return badRequest(Json.newObject()
                    .put("error", "Name is required"));
            }

            String version = json.get("version").asText().trim();
            String name = json.get("name").asText().trim();
            String description = json.has("description") ? json.get("description").asText("") : "";

            // Validate version format
            if (!version.matches("^\\d+\\.\\d+\\.\\d+$")) {
                return badRequest(Json.newObject()
                    .put("error", "Version must follow semantic versioning format (e.g., 1.0.0)"));
            }

            Release release = releaseService.updateRelease(id, version, name, description);
            return ok(Json.toJson(release));

        } catch (IllegalArgumentException e) {
            return badRequest(Json.newObject()
                .put("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return badRequest(Json.newObject()
                .put("error", e.getMessage()));
        } catch (Exception e) {
            return internalServerError(Json.newObject()
                .put("error", "Failed to update release: " + e.getMessage()));
        }
    }

    // POST /api/releases/:id/publish - Publish release
    public Result publishRelease(Http.Request request, Long id) {
        try {
            // Get current user (should be admin)
            User currentUser = getCurrentUser(request);
            if (currentUser == null || !currentUser.hasRole("ADMIN")) {
                return forbidden(Json.newObject()
                    .put("error", "Admin access required"));
            }

            Release release = releaseService.publishRelease(id);
            return ok(Json.toJson(release));

        } catch (IllegalArgumentException e) {
            return badRequest(Json.newObject()
                .put("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return badRequest(Json.newObject()
                .put("error", e.getMessage()));
        } catch (Exception e) {
            return internalServerError(Json.newObject()
                .put("error", "Failed to publish release: " + e.getMessage()));
        }
    }

    // POST /api/releases/:id/archive - Archive release
    public Result archiveRelease(Http.Request request, Long id) {
        try {
            // Get current user (should be admin)
            User currentUser = getCurrentUser(request);
            if (currentUser == null || !currentUser.hasRole("ADMIN")) {
                return forbidden(Json.newObject()
                    .put("error", "Admin access required"));
            }

            Release release = releaseService.archiveRelease(id);
            return ok(Json.toJson(release));

        } catch (IllegalArgumentException e) {
            return badRequest(Json.newObject()
                .put("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return badRequest(Json.newObject()
                .put("error", e.getMessage()));
        } catch (Exception e) {
            return internalServerError(Json.newObject()
                .put("error", "Failed to archive release: " + e.getMessage()));
        }
    }

    // DELETE /api/releases/:id - Delete release (only drafts)
    public Result deleteRelease(Http.Request request, Long id) {
        try {
            // Get current user (should be admin)
            User currentUser = getCurrentUser(request);
            if (currentUser == null || !currentUser.hasRole("ADMIN")) {
                return forbidden(Json.newObject()
                    .put("error", "Admin access required"));
            }

            releaseService.deleteRelease(id);
            return ok(Json.newObject()
                .put("message", "Release deleted successfully"));

        } catch (IllegalArgumentException e) {
            return badRequest(Json.newObject()
                .put("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return badRequest(Json.newObject()
                .put("error", e.getMessage()));
        } catch (Exception e) {
            return internalServerError(Json.newObject()
                .put("error", "Failed to delete release: " + e.getMessage()));
        }
    }

    // GET /api/releases/:id/requirements - Get requirements for release
    public Result getRequirementsForRelease(Long id) {
        try {
            Release release = releaseService.getReleaseById(id);
            if (release == null) {
                return notFound(Json.newObject()
                    .put("error", "Release not found with id: " + id));
            }

            return ok(Json.toJson(releaseService.getRequirementsForRelease(id)));
        } catch (Exception e) {
            return internalServerError(Json.newObject()
                .put("error", "Failed to retrieve requirements: " + e.getMessage()));
        }
    }

    // GET /api/releases/:id/standards - Get standards for release
    public Result getStandardsForRelease(Long id) {
        try {
            Release release = releaseService.getReleaseById(id);
            if (release == null) {
                return notFound(Json.newObject()
                    .put("error", "Release not found with id: " + id));
            }

            return ok(Json.toJson(releaseService.getStandardsForRelease(id)));
        } catch (Exception e) {
            return internalServerError(Json.newObject()
                .put("error", "Failed to retrieve standards: " + e.getMessage()));
        }
    }

    // GET /api/releases/:id/norms - Get norms for release
    public Result getNormsForRelease(Long id) {
        try {
            Release release = releaseService.getReleaseById(id);
            if (release == null) {
                return notFound(Json.newObject()
                    .put("error", "Release not found with id: " + id));
            }

            return ok(Json.toJson(releaseService.getNormsForRelease(id)));
        } catch (Exception e) {
            return internalServerError(Json.newObject()
                .put("error", "Failed to retrieve norms: " + e.getMessage()));
        }
    }

    // GET /api/releases/:id/usecases - Get use cases for release
    public Result getUseCasesForRelease(Long id) {
        try {
            Release release = releaseService.getReleaseById(id);
            if (release == null) {
                return notFound(Json.newObject()
                    .put("error", "Release not found with id: " + id));
            }

            return ok(Json.toJson(releaseService.getUseCasesForRelease(id)));
        } catch (Exception e) {
            return internalServerError(Json.newObject()
                .put("error", "Failed to retrieve use cases: " + e.getMessage()));
        }
    }

    // Helper method to get current user from session
    private User getCurrentUser(Http.Request request) {
        try {
            String username = request.session().get("username").orElse(null);
            if (username != null) {
                return jpaApi.withTransaction(em -> {
                    try {
                        return em.createQuery("SELECT u FROM User u WHERE u.username = :username", User.class)
                            .setParameter("username", username)
                            .getSingleResult();
                    } catch (NoResultException e) {
                        return null;
                    }
                });
            }
        } catch (Exception e) {
            // Log error but don't fail the request
        }
        return null;
    }
}