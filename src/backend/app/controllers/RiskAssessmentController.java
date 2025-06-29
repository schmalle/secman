package controllers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import models.Asset;
import models.RiskAssessment;
import models.User;
import models.UseCase;
import play.db.jpa.JPAApi;
import play.libs.Json;
import play.mvc.Controller;
import play.mvc.Http;
import play.mvc.Result;
import services.NotificationService;
import services.TokenService;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.HashSet;
import java.util.Optional;

@Singleton
public class RiskAssessmentController extends Controller {

    private final JPAApi jpaApi;
    private final NotificationService notificationService;
    private final TokenService tokenService;
    private final ObjectMapper objectMapper;

    @Inject
    public RiskAssessmentController(JPAApi jpaApi, NotificationService notificationService, TokenService tokenService) {
        this.jpaApi = jpaApi;
        this.notificationService = notificationService;
        this.tokenService = tokenService;
        this.objectMapper = new ObjectMapper();
    }

    public Result list() {
        return jpaApi.withTransaction(em -> {
            TypedQuery<RiskAssessment> query = em.createQuery(
                "SELECT ra FROM RiskAssessment ra JOIN FETCH ra.asset ORDER BY ra.createdAt DESC", 
                RiskAssessment.class
            );
            List<RiskAssessment> assessments = query.getResultList();
            return ok(Json.toJson(assessments));
        });
    }

    public Result get(Long id) {
        return jpaApi.withTransaction(em -> {
            RiskAssessment assessment = em.find(RiskAssessment.class, id);
            if (assessment == null) {
                return notFound(Json.toJson("Risk Assessment not found"));
            }
            return ok(Json.toJson(assessment));
        });
    }

    public Result getByAsset(Long assetId) {
        return jpaApi.withTransaction(em -> {
            TypedQuery<RiskAssessment> query = em.createQuery(
                "SELECT ra FROM RiskAssessment ra WHERE ra.asset.id = :assetId ORDER BY ra.createdAt DESC", 
                RiskAssessment.class
            );
            query.setParameter("assetId", assetId);
            List<RiskAssessment> assessments = query.getResultList();
            return ok(Json.toJson(assessments));
        });
    }

    public Result create(Http.Request request) {
        JsonNode json = request.body().asJson();
        if (json == null) {
            return badRequest(Json.toJson("Invalid JSON"));
        }
        
        // Get current user from session
        Optional<String> usernameOpt = request.session().get("username");
        if (usernameOpt.isEmpty()) {
            return unauthorized(Json.toJson("Authentication required"));
        }
        
        try {
            return jpaApi.withTransaction(em -> {
                // Get current user (requestor) from session
                TypedQuery<User> userQuery = em.createQuery(
                    "SELECT u FROM User u WHERE u.username = :username", User.class);
                userQuery.setParameter("username", usernameOpt.get());
                User requestor;
                try {
                    requestor = userQuery.getSingleResult();
                } catch (Exception e) {
                    return unauthorized(Json.toJson("Invalid session"));
                }
                
                Long assetId = json.get("assetId").asLong();
                Asset asset = em.find(Asset.class, assetId);
                if (asset == null) {
                    return badRequest(Json.toJson("Asset not found"));
                }

                Long assessorId = json.get("assessorId").asLong();
                User assessor = em.find(User.class, assessorId);
                if (assessor == null) {
                    return badRequest(Json.toJson("Assessor not found"));
                }

                RiskAssessment assessment = new RiskAssessment();
                assessment.setAsset(asset);
                assessment.setAssessor(assessor);
                assessment.setRequestor(requestor); // Set to current user
                assessment.setEndDate(LocalDate.parse(json.get("endDate").asText()));
                
                // Set start date to today if not provided
                if (json.has("startDate")) {
                    assessment.setStartDate(LocalDate.parse(json.get("startDate").asText()));
                } else {
                    assessment.setStartDate(LocalDate.now());
                }
                
                // Set status to STARTED by default
                assessment.setStatus("STARTED");
                
                // Handle optional respondent
                if (json.has("respondentId") && !json.get("respondentId").isNull()) {
                    Long respondentId = json.get("respondentId").asLong();
                    User respondent = em.find(User.class, respondentId);
                    if (respondent != null) {
                        assessment.setRespondent(respondent);
                    }
                }
                
                if (json.has("notes")) assessment.setNotes(json.get("notes").asText());
                
                // Handle use cases
                if (json.has("useCaseIds")) {
                    Set<UseCase> useCases = new HashSet<>();
                    JsonNode useCaseIdsNode = json.get("useCaseIds");
                    if (useCaseIdsNode.isArray()) {
                        for (JsonNode useCaseIdNode : useCaseIdsNode) {
                            Long useCaseId = useCaseIdNode.asLong();
                            UseCase useCase = em.find(UseCase.class, useCaseId);
                            if (useCase != null) {
                                useCases.add(useCase);
                            }
                        }
                    }
                    assessment.setUseCases(useCases);
                }

                em.persist(assessment);
                
                // Send notification if respondent is specified
                if (assessment.getRespondent() != null) {
                    notificationService.sendAssessmentNotification(assessment, assessment.getRespondent().getEmail());
                }
                
                return created(Json.toJson(assessment));
            });
        } catch (Exception e) {
            return badRequest(Json.toJson("Invalid risk assessment data: " + e.getMessage()));
        }
    }

    public Result update(Long id, Http.Request request) {
        JsonNode json = request.body().asJson();
        if (json == null) {
            return badRequest(Json.toJson("Invalid JSON"));
        }

        return jpaApi.withTransaction(em -> {
            RiskAssessment assessment = em.find(RiskAssessment.class, id);
            if (assessment == null) {
                return notFound(Json.toJson("Risk Assessment not found"));
            }

            try {
                if (json.has("assetId")) {
                    Long assetId = json.get("assetId").asLong();
                    Asset asset = em.find(Asset.class, assetId);
                    if (asset == null) {
                        return badRequest(Json.toJson("Asset not found"));
                    }
                    assessment.setAsset(asset);
                }
                
                if (json.has("assessorId")) {
                    Long assessorId = json.get("assessorId").asLong();
                    User assessor = em.find(User.class, assessorId);
                    if (assessor == null) {
                        return badRequest(Json.toJson("Assessor not found"));
                    }
                    assessment.setAssessor(assessor);
                }
                
                if (json.has("requestorId")) {
                    Long requestorId = json.get("requestorId").asLong();
                    User requestor = em.find(User.class, requestorId);
                    if (requestor == null) {
                        return badRequest(Json.toJson("Requestor not found"));
                    }
                    assessment.setRequestor(requestor);
                }
                
                if (json.has("respondentId")) {
                    if (json.get("respondentId").isNull()) {
                        assessment.setRespondent(null);
                    } else {
                        Long respondentId = json.get("respondentId").asLong();
                        User respondent = em.find(User.class, respondentId);
                        if (respondent == null) {
                            return badRequest(Json.toJson("Respondent not found"));
                        }
                        assessment.setRespondent(respondent);
                    }
                }
                
                if (json.has("startDate")) assessment.setStartDate(LocalDate.parse(json.get("startDate").asText()));
                if (json.has("endDate")) assessment.setEndDate(LocalDate.parse(json.get("endDate").asText()));
                if (json.has("status")) assessment.setStatus(json.get("status").asText());
                if (json.has("notes")) assessment.setNotes(json.get("notes").asText());
                
                // Handle use cases
                if (json.has("useCaseIds")) {
                    Set<UseCase> useCases = new HashSet<>();
                    JsonNode useCaseIdsNode = json.get("useCaseIds");
                    if (useCaseIdsNode.isArray()) {
                        for (JsonNode useCaseIdNode : useCaseIdsNode) {
                            Long useCaseId = useCaseIdNode.asLong();
                            UseCase useCase = em.find(UseCase.class, useCaseId);
                            if (useCase != null) {
                                useCases.add(useCase);
                            }
                        }
                    }
                    assessment.setUseCases(useCases);
                }

                em.merge(assessment);
                return ok(Json.toJson(assessment));
            } catch (Exception e) {
                return badRequest(Json.toJson("Invalid risk assessment data: " + e.getMessage()));
            }
        });
    }

    public Result delete(Long id) {
        return jpaApi.withTransaction(em -> {
            RiskAssessment assessment = em.find(RiskAssessment.class, id);
            if (assessment == null) {
                return notFound(Json.toJson("Risk Assessment not found"));
            }

            em.remove(assessment);
            return ok(Json.toJson("Risk Assessment deleted successfully"));
        });
    }

    public Result sendNotification(Long id, Http.Request request) {
        JsonNode json = request.body().asJson();
        if (json == null || !json.has("respondentEmail")) {
            return badRequest(Json.toJson("Respondent email is required"));
        }

        String respondentEmail = json.get("respondentEmail").asText();

        return jpaApi.withTransaction(em -> {
            RiskAssessment assessment = em.find(RiskAssessment.class, id);
            if (assessment == null) {
                return notFound(Json.toJson("Risk Assessment not found"));
            }

            try {
                notificationService.sendAssessmentNotification(assessment, respondentEmail);
                return ok(Json.toJson("Notification sent successfully"));
            } catch (Exception e) {
                return internalServerError(Json.toJson("Failed to send notification: " + e.getMessage()));
            }
        });
    }

    public Result sendReminder(Long id, Http.Request request) {
        JsonNode json = request.body().asJson();
        if (json == null || !json.has("respondentEmail")) {
            return badRequest(Json.toJson("Respondent email is required"));
        }

        String respondentEmail = json.get("respondentEmail").asText();

        return jpaApi.withTransaction(em -> {
            RiskAssessment assessment = em.find(RiskAssessment.class, id);
            if (assessment == null) {
                return notFound(Json.toJson("Risk Assessment not found"));
            }

            try {
                notificationService.sendReminderNotification(assessment, respondentEmail);
                return ok(Json.toJson("Reminder sent successfully"));
            } catch (Exception e) {
                return internalServerError(Json.toJson("Failed to send reminder: " + e.getMessage()));
            }
        });
    }

    public Result generateToken(Long id, Http.Request request) {
        // Get current user from session
        Optional<String> usernameOpt = request.session().get("username");
        if (usernameOpt.isEmpty()) {
            return unauthorized(Json.toJson("Authentication required"));
        }

        return jpaApi.withTransaction(em -> {
            RiskAssessment assessment = em.find(RiskAssessment.class, id);
            if (assessment == null) {
                return notFound(Json.toJson("Risk Assessment not found"));
            }

            // Get current user
            TypedQuery<User> userQuery = em.createQuery(
                "SELECT u FROM User u WHERE u.username = :username", User.class);
            userQuery.setParameter("username", usernameOpt.get());
            User currentUser;
            try {
                currentUser = userQuery.getSingleResult();
            } catch (Exception e) {
                return unauthorized(Json.toJson("Invalid session"));
            }

            try {
                // Generate token for current user to perform assessment
                models.AssessmentToken assessmentToken = tokenService.createAssessmentToken(assessment, currentUser.getEmail());
                
                // Create response with token
                com.fasterxml.jackson.databind.node.ObjectNode result = objectMapper.createObjectNode();
                result.put("token", assessmentToken.getToken());
                result.put("expiresAt", assessmentToken.getExpiresAt().toString());
                
                return ok(result);
            } catch (Exception e) {
                return internalServerError(Json.toJson("Failed to generate assessment token: " + e.getMessage()));
            }
        });
    }
}
