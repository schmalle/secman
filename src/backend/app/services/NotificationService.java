package services;

import models.AssessmentToken;
import models.RiskAssessment;
import models.Response;
import models.Requirement;
import play.db.jpa.JPAApi;
import play.libs.concurrent.HttpExecutionContext;
import views.html.emails.assessment_notification;
import views.html.emails.completion_summary;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.persistence.TypedQuery;
import java.util.List;
import java.util.concurrent.CompletionStage;

@Singleton
public class NotificationService {

    private final EmailService emailService;
    private final TokenService tokenService;
    private final JPAApi jpaApi;
    private final HttpExecutionContext httpExecutionContext;

    @Inject
    public NotificationService(EmailService emailService, TokenService tokenService, JPAApi jpaApi, HttpExecutionContext httpExecutionContext) {
        this.emailService = emailService;
        this.tokenService = tokenService;
        this.jpaApi = jpaApi;
        this.httpExecutionContext = httpExecutionContext;
    }

    public CompletionStage<Boolean> sendAssessmentNotification(RiskAssessment riskAssessment, String respondentEmail) {
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try {
                // Generate secure token for the respondent
                AssessmentToken token = tokenService.createAssessmentToken(riskAssessment, respondentEmail);
                
                // Get requirements for the assessment
                List<Requirement> requirements = getRequirementsForAssessment(riskAssessment);
                
                // Generate assessment URL
                String assessmentUrl = generateAssessmentUrl(token.getToken());
                
                // Create email content
                String subject = "Risk Assessment Required - " + riskAssessment.getAsset().getName();
                
                // Use Twirl template for email content
                String htmlContent = assessment_notification.render(
                    riskAssessment,
                    requirements,
                    assessmentUrl,
                    token.getExpiresAt()
                ).toString();
                
                // Send email
                return emailService.sendEmail(respondentEmail, subject, "", htmlContent).toCompletableFuture().join();
            } catch (Exception e) {
                e.printStackTrace();
                return false;
            }
        }, httpExecutionContext.current());
    }

    public CompletionStage<Boolean> sendCompletionNotification(RiskAssessment riskAssessment, String respondentEmail) {
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try {
                // Get responses for the assessment
                List<Response> responses = getResponsesForAssessment(riskAssessment.getId(), respondentEmail);
                
                // Create email content for requestor
                String subject = "Risk Assessment Completed - " + riskAssessment.getAsset().getName();
                
                String htmlContent = completion_summary.render(
                    riskAssessment,
                    responses,
                    respondentEmail
                ).toString();
                
                // Send to requestor
                String requestorEmail = riskAssessment.getRequestor().getEmail();
                return emailService.sendEmail(requestorEmail, subject, "", htmlContent).toCompletableFuture().join();
            } catch (Exception e) {
                e.printStackTrace();
                return false;
            }
        }, httpExecutionContext.current());
    }

    public CompletionStage<Boolean> sendReminderNotification(RiskAssessment riskAssessment, String respondentEmail) {
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try {
                // Get existing token or create new one
                AssessmentToken token = tokenService.createAssessmentToken(riskAssessment, respondentEmail);
                
                // Generate assessment URL
                String assessmentUrl = generateAssessmentUrl(token.getToken());
                
                // Create reminder email content
                String subject = "Reminder: Risk Assessment Due Soon - " + riskAssessment.getAsset().getName();
                
                // For now, reuse the assessment notification template with reminder context
                List<Requirement> requirements = getRequirementsForAssessment(riskAssessment);
                String htmlContent = assessment_notification.render(
                    riskAssessment,
                    requirements,
                    assessmentUrl,
                    token.getExpiresAt()
                ).toString();
                
                // Send email
                return emailService.sendEmail(respondentEmail, subject, "", htmlContent).toCompletableFuture().join();
            } catch (Exception e) {
                e.printStackTrace();
                return false;
            }
        }, httpExecutionContext.current());
    }

    private List<Requirement> getRequirementsForAssessment(RiskAssessment riskAssessment) {
        return jpaApi.withTransaction(em -> {
            // Use the same logic as ResponseController - try direct relationship first
            TypedQuery<Requirement> query = em.createQuery(
                "SELECT DISTINCT r FROM Requirement r JOIN r.usecases uc WHERE uc IN :useCases ORDER BY r.id",
                Requirement.class
            );
            query.setParameter("useCases", riskAssessment.getUseCases());
            List<Requirement> requirements = query.getResultList();
            
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
            
            // If still no requirements, get all requirements as fallback
            if (requirements.isEmpty()) {
                TypedQuery<Requirement> allQuery = em.createQuery(
                    "SELECT r FROM Requirement r ORDER BY r.id",
                    Requirement.class
                );
                requirements = allQuery.getResultList();
            }
            
            return requirements;
        });
    }

    private List<Response> getResponsesForAssessment(Long riskAssessmentId, String respondentEmail) {
        return jpaApi.withTransaction(em -> {
            TypedQuery<Response> query = em.createQuery(
                "SELECT r FROM Response r WHERE r.riskAssessment.id = :assessmentId AND r.respondentEmail = :email ORDER BY r.requirement.id",
                Response.class
            );
            query.setParameter("assessmentId", riskAssessmentId);
            query.setParameter("email", respondentEmail);
            return query.getResultList();
        });
    }

    private String generateAssessmentUrl(String token) {
        // In production, this should be configurable
        String baseUrl = "http://localhost:4321"; // Default for development
        return baseUrl + "/respond/" + token;
    }
}