package controllers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import models.*;
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
import java.util.List;
import java.util.Optional;

@Singleton
public class ResponseController extends Controller {

    private final JPAApi jpaApi;
    private final TokenService tokenService;
    private final NotificationService notificationService;
    private final ObjectMapper objectMapper;

    @Inject
    public ResponseController(JPAApi jpaApi, TokenService tokenService, NotificationService notificationService) {
        this.jpaApi = jpaApi;
        this.tokenService = tokenService;
        this.notificationService = notificationService;
        this.objectMapper = new ObjectMapper();
    }

    public Result getAssessmentByToken(String token) {
        Optional<AssessmentToken> tokenOpt = tokenService.validateToken(token);
        if (tokenOpt.isEmpty()) {
            return unauthorized(Json.toJson("Invalid or expired token"));
        }

        return jpaApi.withTransaction(em -> {
            AssessmentToken assessmentToken = tokenOpt.get();
            
            // Fetch RiskAssessment with all lazy associations to avoid LazyInitializationException
            TypedQuery<RiskAssessment> raQuery = em.createQuery(
                "SELECT ra FROM RiskAssessment ra " +
                "JOIN FETCH ra.asset " +
                "JOIN FETCH ra.assessor " +
                "JOIN FETCH ra.requestor " +
                "LEFT JOIN FETCH ra.respondent " +
                "LEFT JOIN FETCH ra.useCases " +
                "WHERE ra.id = :assessmentId", 
                RiskAssessment.class
            );
            raQuery.setParameter("assessmentId", assessmentToken.getRiskAssessment().getId());
            RiskAssessment riskAssessment = raQuery.getSingleResult();
            
            // Get requirements for the assessment
            List<Requirement> requirements;
            if (riskAssessment.getUseCases() != null && !riskAssessment.getUseCases().isEmpty()) {
                // Try direct relationship first
                TypedQuery<Requirement> reqQuery = em.createQuery(
                    "SELECT DISTINCT r FROM Requirement r JOIN r.usecases uc WHERE uc IN :useCases ORDER BY r.id",
                    Requirement.class
                );
                reqQuery.setParameter("useCases", riskAssessment.getUseCases());
                requirements = reqQuery.getResultList();
                
                // If no direct requirements found, try through standards (Standard -> UseCase -> Requirements)
                if (requirements.isEmpty()) {
                    TypedQuery<Requirement> standardQuery = em.createQuery(
                        "SELECT DISTINCT r FROM Requirement r JOIN r.usecases ruc " +
                        "WHERE ruc IN (SELECT uc FROM Standard s JOIN s.useCases uc WHERE uc IN :useCases) ORDER BY r.id",
                        Requirement.class
                    );
                    standardQuery.setParameter("useCases", riskAssessment.getUseCases());
                    requirements = standardQuery.getResultList();
                }
                
                // If still no requirements found, get all available requirements
                if (requirements.isEmpty()) {
                    TypedQuery<Requirement> allQuery = em.createQuery(
                        "SELECT r FROM Requirement r ORDER BY r.id",
                        Requirement.class
                    );
                    requirements = allQuery.getResultList();
                }
            } else {
                // No use cases specified, get all requirements
                TypedQuery<Requirement> allQuery = em.createQuery(
                    "SELECT r FROM Requirement r ORDER BY r.id",
                    Requirement.class
                );
                requirements = allQuery.getResultList();
            }
            
            // Get existing responses
            TypedQuery<Response> respQuery = em.createQuery(
                "SELECT r FROM Response r WHERE r.riskAssessment.id = :assessmentId AND r.respondentEmail = :email",
                Response.class
            );
            respQuery.setParameter("assessmentId", riskAssessment.getId());
            respQuery.setParameter("email", assessmentToken.getRespondentEmail());
            List<Response> existingResponses = respQuery.getResultList();
            
            // Create response object
            com.fasterxml.jackson.databind.node.ObjectNode result = objectMapper.createObjectNode();
            result.set("riskAssessment", Json.toJson(riskAssessment));
            result.set("requirements", Json.toJson(requirements));
            result.set("existingResponses", Json.toJson(existingResponses));
            result.put("respondentEmail", assessmentToken.getRespondentEmail());
            result.put("expiresAt", assessmentToken.getExpiresAt().toString());
            
            return ok(result);
        });
    }

    public Result saveResponse(String token, Http.Request request) {
        JsonNode json = request.body().asJson();
        if (json == null) {
            return badRequest(Json.toJson("Invalid JSON"));
        }

        Optional<AssessmentToken> tokenOpt = tokenService.validateToken(token);
        if (tokenOpt.isEmpty()) {
            return unauthorized(Json.toJson("Invalid or expired token"));
        }

        try {
            return jpaApi.withTransaction(em -> {
                AssessmentToken assessmentToken = tokenOpt.get();
                
                // Fetch RiskAssessment with all lazy associations to avoid LazyInitializationException
                TypedQuery<RiskAssessment> raQuery = em.createQuery(
                    "SELECT ra FROM RiskAssessment ra " +
                    "JOIN FETCH ra.asset " +
                    "JOIN FETCH ra.assessor " +
                    "JOIN FETCH ra.requestor " +
                    "LEFT JOIN FETCH ra.respondent " +
                    "LEFT JOIN FETCH ra.useCases " +
                    "WHERE ra.id = :assessmentId", 
                    RiskAssessment.class
                );
                raQuery.setParameter("assessmentId", assessmentToken.getRiskAssessment().getId());
                RiskAssessment riskAssessment = raQuery.getSingleResult();
                String respondentEmail = assessmentToken.getRespondentEmail();
                
                Long requirementId = json.get("requirementId").asLong();
                String answerStr = json.get("answer").asText().toUpperCase();
                String comment = json.has("comment") ? json.get("comment").asText() : null;
                
                Response.AnswerType answer = Response.AnswerType.valueOf(answerStr);
                
                Requirement requirement = em.find(Requirement.class, requirementId);
                if (requirement == null) {
                    return badRequest(Json.toJson("Requirement not found"));
                }
                
                // Check if response already exists
                TypedQuery<Response> existingQuery = em.createQuery(
                    "SELECT r FROM Response r WHERE r.riskAssessment.id = :assessmentId AND r.requirement.id = :reqId AND r.respondentEmail = :email",
                    Response.class
                );
                existingQuery.setParameter("assessmentId", riskAssessment.getId());
                existingQuery.setParameter("reqId", requirementId);
                existingQuery.setParameter("email", respondentEmail);
                
                Response response;
                try {
                    response = existingQuery.getSingleResult();
                    response.setAnswer(answer);
                    response.setComment(comment);
                    em.merge(response);
                } catch (Exception e) {
                    // Create new response
                    response = new Response(riskAssessment, requirement, respondentEmail, answer);
                    response.setComment(comment);
                    em.persist(response);
                }
                
                return ok(Json.toJson(response));
            });
        } catch (Exception e) {
            return badRequest(Json.toJson("Invalid response data: " + e.getMessage()));
        }
    }

    public Result submitAssessment(String token, Http.Request request) {
        Optional<AssessmentToken> tokenOpt = tokenService.validateToken(token);
        if (tokenOpt.isEmpty()) {
            return unauthorized(Json.toJson("Invalid or expired token"));
        }

        return jpaApi.withTransaction(em -> {
            AssessmentToken assessmentToken = tokenOpt.get();
            RiskAssessment riskAssessment = assessmentToken.getRiskAssessment();
            String respondentEmail = assessmentToken.getRespondentEmail();
            
            // Check if all requirements have responses (using correct relationship flow)
            TypedQuery<Long> reqCountQuery = em.createQuery(
                "SELECT COUNT(DISTINCT r.id) FROM Requirement r JOIN r.usecases ruc WHERE ruc IN :useCases",
                Long.class
            );
            reqCountQuery.setParameter("useCases", riskAssessment.getUseCases());
            Long totalRequirements = reqCountQuery.getSingleResult();
            
            TypedQuery<Long> respCountQuery = em.createQuery(
                "SELECT COUNT(r) FROM Response r WHERE r.riskAssessment.id = :assessmentId AND r.respondentEmail = :email",
                Long.class
            );
            respCountQuery.setParameter("assessmentId", riskAssessment.getId());
            respCountQuery.setParameter("email", respondentEmail);
            Long totalResponses = respCountQuery.getSingleResult();
            
            if (!totalRequirements.equals(totalResponses)) {
                return badRequest(Json.toJson("All requirements must be answered before submission"));
            }
            
            // Mark token as used
            assessmentToken.markAsUsed();
            em.merge(assessmentToken);
            
            // Update risk assessment status to COMPLETED
            riskAssessment.setStatus("COMPLETED");
            em.merge(riskAssessment);
            
            // Send completion notification asynchronously
            notificationService.sendCompletionNotification(riskAssessment, respondentEmail);
            
            return ok(Json.toJson("Assessment submitted successfully"));
        });
    }

    public Result getResponses(Long riskAssessmentId) {
        return jpaApi.withTransaction(em -> {
            TypedQuery<Response> query = em.createQuery(
                "SELECT r FROM Response r WHERE r.riskAssessment.id = :assessmentId ORDER BY r.requirement.id",
                Response.class
            );
            query.setParameter("assessmentId", riskAssessmentId);
            List<Response> responses = query.getResultList();
            return ok(Json.toJson(responses));
        });
    }

    public Result getResponsesByEmail(Long riskAssessmentId, String email) {
        return jpaApi.withTransaction(em -> {
            TypedQuery<Response> query = em.createQuery(
                "SELECT r FROM Response r WHERE r.riskAssessment.id = :assessmentId AND r.respondentEmail = :email ORDER BY r.requirement.id",
                Response.class
            );
            query.setParameter("assessmentId", riskAssessmentId);
            query.setParameter("email", email);
            List<Response> responses = query.getResultList();
            return ok(Json.toJson(responses));
        });
    }

    public Result deleteResponse(Long responseId) {
        return jpaApi.withTransaction(em -> {
            Response response = em.find(Response.class, responseId);
            if (response == null) {
                return notFound(Json.toJson("Response not found"));
            }
            
            em.remove(response);
            return ok(Json.toJson("Response deleted successfully"));
        });
    }
}
