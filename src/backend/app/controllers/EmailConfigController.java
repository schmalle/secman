package controllers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import models.EmailConfig;
import play.db.jpa.JPAApi;
import play.libs.Json;
import play.mvc.Controller;
import play.mvc.Http;
import play.mvc.Result;
import services.EmailService;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.persistence.TypedQuery;
import java.util.List;
import java.util.Optional;

@Singleton
public class EmailConfigController extends Controller {

    private final JPAApi jpaApi;
    private final EmailService emailService;
    private final ObjectMapper objectMapper;

    @Inject
    public EmailConfigController(JPAApi jpaApi, EmailService emailService) {
        this.jpaApi = jpaApi;
        this.emailService = emailService;
        this.objectMapper = new ObjectMapper();
    }

    public Result list(Http.Request request) {
        // Check if user is authenticated
        Optional<String> usernameOpt = request.session().get("username");
        if (usernameOpt.isEmpty()) {
            return unauthorized(Json.toJson("Authentication required"));
        }

        return jpaApi.withTransaction(em -> {
            TypedQuery<EmailConfig> query = em.createQuery(
                "SELECT ec FROM EmailConfig ec ORDER BY ec.updatedAt DESC",
                EmailConfig.class
            );
            List<EmailConfig> configs = query.getResultList();
            
            // Hide sensitive information like passwords in the list
            for (EmailConfig config : configs) {
                if (config.getSmtpPassword() != null && !config.getSmtpPassword().isEmpty()) {
                    config.setSmtpPassword("***HIDDEN***");
                }
            }
            
            return ok(Json.toJson(configs));
        });
    }

    public Result get(Http.Request request, Long id) {
        // Check if user is authenticated
        Optional<String> usernameOpt = request.session().get("username");
        if (usernameOpt.isEmpty()) {
            return unauthorized(Json.toJson("Authentication required"));
        }

        return jpaApi.withTransaction(em -> {
            EmailConfig config = em.find(EmailConfig.class, id);
            if (config == null) {
                return notFound(Json.toJson("Email configuration not found"));
            }
            
            // Hide password in single item view too
            if (config.getSmtpPassword() != null && !config.getSmtpPassword().isEmpty()) {
                config.setSmtpPassword("***HIDDEN***");
            }
            
            return ok(Json.toJson(config));
        });
    }

    public Result create(Http.Request request) {
        JsonNode json = request.body().asJson();
        if (json == null) {
            return badRequest(Json.toJson("Invalid JSON"));
        }

        // Check if user is authenticated
        Optional<String> usernameOpt = request.session().get("username");
        if (usernameOpt.isEmpty()) {
            return unauthorized(Json.toJson("Authentication required"));
        }

        try {
            return jpaApi.withTransaction(em -> {
                EmailConfig config = new EmailConfig();
                updateConfigFromJson(config, json);
                
                // If this is marked as active, deactivate others
                if (Boolean.TRUE.equals(config.getIsActive())) {
                    em.createQuery("UPDATE EmailConfig ec SET ec.isActive = false").executeUpdate();
                }
                
                em.persist(config);
                return created(Json.toJson(config));
            });
        } catch (Exception e) {
            return badRequest(Json.toJson("Invalid email configuration data: " + e.getMessage()));
        }
    }

    public Result update(Http.Request request, Long id) {
        JsonNode json = request.body().asJson();
        if (json == null) {
            return badRequest(Json.toJson("Invalid JSON"));
        }

        // Check if user is authenticated
        Optional<String> usernameOpt = request.session().get("username");
        if (usernameOpt.isEmpty()) {
            return unauthorized(Json.toJson("Authentication required"));
        }

        return jpaApi.withTransaction(em -> {
            EmailConfig config = em.find(EmailConfig.class, id);
            if (config == null) {
                return notFound(Json.toJson("Email configuration not found"));
            }

            try {
                updateConfigFromJson(config, json);
                
                // If this is marked as active, deactivate others
                if (Boolean.TRUE.equals(config.getIsActive())) {
                    em.createQuery("UPDATE EmailConfig ec SET ec.isActive = false WHERE ec.id != :id")
                      .setParameter("id", id)
                      .executeUpdate();
                }
                
                em.merge(config);
                return ok(Json.toJson(config));
            } catch (Exception e) {
                return badRequest(Json.toJson("Invalid email configuration data: " + e.getMessage()));
            }
        });
    }

    public Result delete(Http.Request request, Long id) {
        // Check if user is authenticated
        Optional<String> usernameOpt = request.session().get("username");
        if (usernameOpt.isEmpty()) {
            return unauthorized(Json.toJson("Authentication required"));
        }

        return jpaApi.withTransaction(em -> {
            EmailConfig config = em.find(EmailConfig.class, id);
            if (config == null) {
                return notFound(Json.toJson("Email configuration not found"));
            }

            em.remove(config);
            return ok(Json.toJson("Email configuration deleted successfully"));
        });
    }

    public Result testConfiguration(Http.Request request, Long id) {
        JsonNode json = request.body().asJson();
        if (json == null || !json.has("testEmail")) {
            return badRequest(Json.toJson("Test email address is required"));
        }

        String testEmail = json.get("testEmail").asText();

        // Check if user is authenticated
        Optional<String> usernameOpt = request.session().get("username");
        if (usernameOpt.isEmpty()) {
            return unauthorized(Json.toJson("Authentication required"));
        }

        return jpaApi.withTransaction(em -> {
            EmailConfig config = em.find(EmailConfig.class, id);
            if (config == null) {
                return notFound(Json.toJson("Email configuration not found"));
            }

            // Temporarily activate this configuration for testing
            Boolean originalActive = config.getIsActive();
            config.setIsActive(true);
            em.merge(config);

            try {
                boolean success = emailService.sendEmail(
                    testEmail,
                    "Test Email - SecMan Configuration",
                    "This is a test email from SecMan to verify SMTP configuration.",
                    "<h2>Test Email</h2><p>This is a test email from SecMan to verify SMTP configuration.</p><p>If you receive this email, your SMTP settings are working correctly.</p>"
                ).toCompletableFuture().join();

                if (success) {
                    return ok(Json.toJson("Test email sent successfully"));
                } else {
                    return internalServerError(Json.toJson("Failed to send test email"));
                }
            } finally {
                // Restore original active status
                config.setIsActive(originalActive);
                em.merge(config);
            }
        });
    }

    public Result getActiveConfiguration(Http.Request request) {
        // Check if user is authenticated
        Optional<String> usernameOpt = request.session().get("username");
        if (usernameOpt.isEmpty()) {
            return unauthorized(Json.toJson("Authentication required"));
        }

        return jpaApi.withTransaction(em -> {
            TypedQuery<EmailConfig> query = em.createQuery(
                "SELECT ec FROM EmailConfig ec WHERE ec.isActive = true ORDER BY ec.updatedAt DESC",
                EmailConfig.class
            );
            query.setMaxResults(1);
            
            try {
                EmailConfig config = query.getSingleResult();
                // Hide password
                if (config.getSmtpPassword() != null && !config.getSmtpPassword().isEmpty()) {
                    config.setSmtpPassword("***HIDDEN***");
                }
                return ok(Json.toJson(config));
            } catch (Exception e) {
                return notFound(Json.toJson("No active email configuration found"));
            }
        });
    }

    private void updateConfigFromJson(EmailConfig config, JsonNode json) {
        if (json.has("smtpHost")) config.setSmtpHost(json.get("smtpHost").asText());
        if (json.has("smtpPort")) config.setSmtpPort(json.get("smtpPort").asInt());
        if (json.has("smtpUsername")) config.setSmtpUsername(json.get("smtpUsername").asText());
        
        // Only update password if it's not the hidden placeholder
        if (json.has("smtpPassword")) {
            String password = json.get("smtpPassword").asText();
            if (!"***HIDDEN***".equals(password)) {
                config.setSmtpPassword(password);
            }
        }
        
        if (json.has("smtpTls")) config.setSmtpTls(json.get("smtpTls").asBoolean());
        if (json.has("smtpSsl")) config.setSmtpSsl(json.get("smtpSsl").asBoolean());
        if (json.has("fromEmail")) config.setFromEmail(json.get("fromEmail").asText());
        if (json.has("fromName")) config.setFromName(json.get("fromName").asText());
        if (json.has("isActive")) config.setIsActive(json.get("isActive").asBoolean());
    }
}