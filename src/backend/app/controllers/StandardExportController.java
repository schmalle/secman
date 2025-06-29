package controllers;

import com.fasterxml.jackson.databind.node.ObjectNode;
import models.Release;
import models.Standard;
import play.libs.Json;
import play.mvc.Controller;
import play.mvc.Http;
import play.mvc.Result;
import play.mvc.With;
import services.ReleaseService;
import actions.Secured;

import jakarta.inject.Inject;
import java.util.List;

@With(Secured.class)
public class StandardExportController extends Controller {

    private final ReleaseService releaseService;

    @Inject
    public StandardExportController(ReleaseService releaseService) {
        this.releaseService = releaseService;
    }

    // GET /api/standards/export/docx - Export current/latest standards
    public Result exportCurrentStandards(Http.Request request) {
        try {
            Release currentRelease = releaseService.getCurrentActiveRelease();
            if (currentRelease == null) {
                return badRequest(Json.newObject()
                    .put("error", "No active release found"));
            }
            
            return exportStandardsForRelease(request, currentRelease.getId());
        } catch (Exception e) {
            return internalServerError(Json.newObject()
                .put("error", "Failed to export current standards: " + e.getMessage()));
        }
    }

    // GET /api/standards/export/docx/release/:releaseId - Export standards for specific release
    public Result exportStandardsByRelease(Http.Request request, Long releaseId) {
        return exportStandardsForRelease(request, releaseId);
    }

    // GET /api/standards/export/docx/version/:version - Export standards for specific version
    public Result exportStandardsByVersion(Http.Request request, String version) {
        try {
            Release release = releaseService.getReleaseByVersion(version);
            if (release == null) {
                return notFound(Json.newObject()
                    .put("error", "Release not found with version: " + version));
            }
            
            return exportStandardsForRelease(request, release.getId());
        } catch (Exception e) {
            return internalServerError(Json.newObject()
                .put("error", "Failed to export standards for version " + version + ": " + e.getMessage()));
        }
    }

    // GET /api/standards/export/docx/standard/:standardId/all-versions - Export all versions of a standard
    public Result exportStandardAllVersions(Http.Request request, Long standardId) {
        try {
            // TODO: Implement multi-version export
            return notImplemented(Json.newObject()
                .put("error", "Multi-version export not yet implemented"));
        } catch (Exception e) {
            return internalServerError(Json.newObject()
                .put("error", "Failed to export standard versions: " + e.getMessage()));
        }
    }

    // GET /api/standards/export/docx/compare - Export comparison between releases
    public Result exportStandardsComparison(Http.Request request) {
        try {
            String fromReleaseIdStr = request.getQueryString("fromRelease");
            String toReleaseIdStr = request.getQueryString("toRelease");
            
            if (fromReleaseIdStr == null || toReleaseIdStr == null) {
                return badRequest(Json.newObject()
                    .put("error", "Both fromRelease and toRelease query parameters are required"));
            }
            
            // TODO: Implement comparison export
            return notImplemented(Json.newObject()
                .put("error", "Standards comparison export not yet implemented"));
        } catch (Exception e) {
            return internalServerError(Json.newObject()
                .put("error", "Failed to export standards comparison: " + e.getMessage()));
        }
    }

    // GET /api/standards/export/standard/:standardId/versions - Get version history for a standard
    public Result getStandardVersions(Long standardId) {
        try {
            // TODO: Implement version history retrieval
            return notImplemented(Json.newObject()
                .put("error", "Standard version history not yet implemented"));
        } catch (Exception e) {
            return internalServerError(Json.newObject()
                .put("error", "Failed to get standard versions: " + e.getMessage()));
        }
    }

    // Helper method to export standards for a specific release
    private Result exportStandardsForRelease(Http.Request request, Long releaseId) {
        try {
            Release release = releaseService.getReleaseById(releaseId);
            if (release == null) {
                return notFound(Json.newObject()
                    .put("error", "Release not found with id: " + releaseId));
            }

            List<Standard> standards = releaseService.getStandardsForRelease(releaseId);
            
            // For now, return JSON data (TODO: implement actual document generation)
            ObjectNode response = Json.newObject();
            response.put("releaseVersion", release.getVersion());
            response.put("releaseName", release.getName());
            response.put("standardsCount", standards.size());
            response.set("standards", Json.toJson(standards));
            
            return ok(response);
            
        } catch (Exception e) {
            return internalServerError(Json.newObject()
                .put("error", "Failed to export standards for release " + releaseId + ": " + e.getMessage()));
        }
    }

    private Result notImplemented(ObjectNode errorMessage) {
        return status(501, errorMessage);
    }
}