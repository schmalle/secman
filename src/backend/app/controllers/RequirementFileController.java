package controllers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import models.*;
import play.db.jpa.JPAApi;
import play.libs.Json;
import play.mvc.*;
import actions.Secured;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.Arrays;
import play.libs.Files.TemporaryFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Singleton
@With(Secured.class)
public class RequirementFileController extends Controller {

    private final JPAApi jpaApi;
    private final ObjectMapper objectMapper;
    private static final Logger log = LoggerFactory.getLogger(RequirementFileController.class);
    
    private static final long MAX_FILE_SIZE = 50 * 1024 * 1024; // 50MB
    private static final List<String> ALLOWED_CONTENT_TYPES = Arrays.asList(
        "application/pdf",
        "application/msword",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/vnd.ms-excel",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "image/jpeg",
        "image/png",
        "image/gif",
        "text/plain",
        "text/csv"
    );
    
    private static final String UPLOAD_DIR = "uploads/requirement-files";

    @Inject
    public RequirementFileController(JPAApi jpaApi) {
        this.jpaApi = jpaApi;
        this.objectMapper = new ObjectMapper();
        
        // Create upload directory if it doesn't exist
        try {
            Files.createDirectories(Paths.get(UPLOAD_DIR));
        } catch (IOException e) {
            log.error("Failed to create upload directory: " + UPLOAD_DIR, e);
        }
    }

    @BodyParser.Of(BodyParser.MultipartFormData.class)
    public Result uploadFile(Long riskAssessmentId, Long requirementId, Http.Request request) {
        log.info("Received file upload request for risk assessment {} and requirement {}", 
                riskAssessmentId, requirementId);

        // Get current user from session
        Optional<String> usernameOpt = request.session().get("username");
        if (usernameOpt.isEmpty()) {
            return unauthorized(Json.newObject().put("error", "Authentication required"));
        }

        Http.MultipartFormData<TemporaryFile> body = request.body().asMultipartFormData();
        Http.MultipartFormData.FilePart<TemporaryFile> filePart = body.getFile("file");

        if (filePart == null) {
            log.warn("No file found in the request");
            return badRequest(Json.newObject().put("error", "No file provided"));
        }

        String originalFilename = filePart.getFilename();
        String contentType = filePart.getContentType();
        long fileSize = filePart.getFileSize();
        TemporaryFile temporaryFile = filePart.getRef();

        log.info("File details: name={}, size={}, contentType={}", originalFilename, fileSize, contentType);

        // Validate file
        if (fileSize > MAX_FILE_SIZE) {
            return badRequest(Json.newObject().put("error", "File size exceeds 50MB limit"));
        }

        if (contentType == null || !ALLOWED_CONTENT_TYPES.contains(contentType)) {
            return badRequest(Json.newObject().put("error", "File type not allowed"));
        }

        try {
            return jpaApi.withTransaction(em -> {
                // Verify user exists
                User user = getUserByUsername(em, usernameOpt.get());
                if (user == null) {
                    return unauthorized(Json.newObject().put("error", "Invalid session"));
                }

                // Verify risk assessment and requirement exist
                RiskAssessment riskAssessment = em.find(RiskAssessment.class, riskAssessmentId);
                if (riskAssessment == null) {
                    return notFound(Json.newObject().put("error", "Risk assessment not found"));
                }

                Requirement requirement = em.find(Requirement.class, requirementId);
                if (requirement == null) {
                    return notFound(Json.newObject().put("error", "Requirement not found"));
                }

                // Generate unique filename
                String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
                String extension = getFileExtension(originalFilename);
                String uniqueFilename = String.format("%s_%s_%s%s", 
                    riskAssessmentId, requirementId, timestamp, extension);

                // Save file to disk
                Path targetPath = Paths.get(UPLOAD_DIR, uniqueFilename);
                try {
                    Files.copy(temporaryFile.path(), targetPath, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e) {
                    log.error("Failed to save file", e);
                    return internalServerError(Json.newObject().put("error", "Failed to save file"));
                }

                // Save file metadata to database
                RequirementFile requirementFile = new RequirementFile(
                    uniqueFilename, originalFilename, targetPath.toString(), 
                    fileSize, contentType, user
                );
                em.persist(requirementFile);
                em.flush();

                // Create link between risk assessment, requirement, and file
                RiskAssessmentRequirementFile link = new RiskAssessmentRequirementFile(
                    riskAssessment, requirement, requirementFile, user
                );
                em.persist(link);
                em.flush();

                log.info("File uploaded successfully: {}", uniqueFilename);

                // Return file metadata
                ObjectNode result = Json.newObject();
                result.put("id", requirementFile.getId());
                result.put("filename", requirementFile.getOriginalFilename());
                result.put("size", requirementFile.getFileSize());
                result.put("contentType", requirementFile.getContentType());
                result.put("uploadedBy", user.getUsername());
                result.put("createdAt", requirementFile.getCreatedAt().toString());

                return ok(result);
            });

        } catch (Exception e) {
            log.error("Error uploading file", e);
            return internalServerError(Json.newObject().put("error", "Internal server error"));
        }
    }

    public Result listFiles(Long riskAssessmentId, Long requirementId) {
        return jpaApi.withTransaction(em -> {
            TypedQuery<RiskAssessmentRequirementFile> query = em.createQuery(
                "SELECT rf FROM RiskAssessmentRequirementFile rf " +
                "JOIN FETCH rf.file f " +
                "JOIN FETCH rf.uploadedBy u " +
                "WHERE rf.riskAssessment.id = :riskAssessmentId " +
                "AND rf.requirement.id = :requirementId " +
                "ORDER BY rf.createdAt DESC", 
                RiskAssessmentRequirementFile.class
            );
            query.setParameter("riskAssessmentId", riskAssessmentId);
            query.setParameter("requirementId", requirementId);

            List<RiskAssessmentRequirementFile> files = query.getResultList();

            ArrayNode result = Json.newArray();
            for (RiskAssessmentRequirementFile link : files) {
                RequirementFile file = link.getFile();
                ObjectNode fileJson = Json.newObject();
                fileJson.put("id", file.getId());
                fileJson.put("filename", file.getOriginalFilename());
                fileJson.put("size", file.getFileSize());
                fileJson.put("contentType", file.getContentType());
                fileJson.put("uploadedBy", link.getUploadedBy().getUsername());
                fileJson.put("createdAt", link.getCreatedAt().toString());
                result.add(fileJson);
            }

            return ok(result);
        });
    }

    public Result downloadFile(Long fileId, Http.Request request) {
        // Get current user from session
        Optional<String> usernameOpt = request.session().get("username");
        if (usernameOpt.isEmpty()) {
            return unauthorized(Json.newObject().put("error", "Authentication required"));
        }

        return jpaApi.withTransaction(em -> {
            RequirementFile file = em.find(RequirementFile.class, fileId);
            if (file == null) {
                return notFound(Json.newObject().put("error", "File not found"));
            }

            // Verify user has access to this file through risk assessment
            TypedQuery<RiskAssessmentRequirementFile> query = em.createQuery(
                "SELECT rf FROM RiskAssessmentRequirementFile rf " +
                "WHERE rf.file.id = :fileId", 
                RiskAssessmentRequirementFile.class
            );
            query.setParameter("fileId", fileId);
            List<RiskAssessmentRequirementFile> links = query.getResultList();

            if (links.isEmpty()) {
                return notFound(Json.newObject().put("error", "File access not found"));
            }

            // Check if file exists on disk
            Path filePath = Paths.get(file.getFilePath());
            if (!Files.exists(filePath)) {
                log.error("File not found on disk: {}", file.getFilePath());
                return notFound(Json.newObject().put("error", "File not found on disk"));
            }

            try {
                return ok(Files.readAllBytes(filePath))
                    .as(file.getContentType())
                    .withHeader("Content-Disposition", 
                        "attachment; filename=\"" + file.getOriginalFilename() + "\"");
            } catch (IOException e) {
                log.error("Error reading file", e);
                return internalServerError(Json.newObject().put("error", "Error reading file"));
            }
        });
    }

    public Result deleteFile(Long fileId, Http.Request request) {
        // Get current user from session
        Optional<String> usernameOpt = request.session().get("username");
        if (usernameOpt.isEmpty()) {
            return unauthorized(Json.newObject().put("error", "Authentication required"));
        }

        return jpaApi.withTransaction(em -> {
            User user = getUserByUsername(em, usernameOpt.get());
            if (user == null) {
                return unauthorized(Json.newObject().put("error", "Invalid session"));
            }

            RequirementFile file = em.find(RequirementFile.class, fileId);
            if (file == null) {
                return notFound(Json.newObject().put("error", "File not found"));
            }

            // Check if user is admin or the uploader
            boolean canDelete = user.isAdmin() || 
                               file.getUploadedBy().getId().equals(user.getId());
            
            if (!canDelete) {
                return forbidden(Json.newObject().put("error", "Insufficient permissions"));
            }

            // Delete file from disk
            try {
                Path filePath = Paths.get(file.getFilePath());
                if (Files.exists(filePath)) {
                    Files.delete(filePath);
                }
            } catch (IOException e) {
                log.warn("Failed to delete file from disk: {}", file.getFilePath(), e);
            }

            // Delete from database (cascade will handle junction table)
            em.remove(file);

            log.info("File deleted: {} by user {}", file.getOriginalFilename(), user.getUsername());

            return ok(Json.newObject().put("message", "File deleted successfully"));
        });
    }

    private User getUserByUsername(EntityManager em, String username) {
        try {
            TypedQuery<User> query = em.createQuery(
                "SELECT u FROM User u WHERE u.username = :username", User.class);
            query.setParameter("username", username);
            return query.getSingleResult();
        } catch (Exception e) {
            log.debug("User not found: {}", username);
            return null;
        }
    }

    private String getFileExtension(String filename) {
        if (filename == null || !filename.contains(".")) {
            return "";
        }
        return filename.substring(filename.lastIndexOf("."));
    }
}