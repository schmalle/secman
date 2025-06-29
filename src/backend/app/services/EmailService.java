package services;

import models.EmailConfig;
import play.db.jpa.JPAApi;
import play.libs.concurrent.HttpExecutionContext;
import play.twirl.api.Html;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.persistence.TypedQuery;
import javax.mail.*;
import javax.mail.internet.*;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

@Singleton
public class EmailService {

    private final JPAApi jpaApi;
    private final HttpExecutionContext httpExecutionContext;

    @Inject
    public EmailService(JPAApi jpaApi, HttpExecutionContext httpExecutionContext) {
        this.jpaApi = jpaApi;
        this.httpExecutionContext = httpExecutionContext;
    }

    public CompletionStage<Boolean> sendEmail(String to, String subject, String textContent, String htmlContent) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return jpaApi.withTransaction(em -> {
                    // Get active email configuration
                    TypedQuery<EmailConfig> query = em.createQuery(
                        "SELECT ec FROM EmailConfig ec WHERE ec.isActive = true ORDER BY ec.updatedAt DESC", 
                        EmailConfig.class
                    );
                    query.setMaxResults(1);
                    
                    EmailConfig config;
                    try {
                        config = query.getSingleResult();
                    } catch (Exception e) {
                        throw new RuntimeException("No active email configuration found");
                    }

                    return sendEmailWithConfig(config, to, subject, textContent, htmlContent);
                });
            } catch (Exception e) {
                e.printStackTrace();
                return false;
            }
        }, httpExecutionContext.current());
    }

    public CompletionStage<Boolean> sendHtmlEmail(String to, String subject, Html htmlContent) {
        String html = htmlContent.toString();
        // Create simple text version by stripping HTML tags
        String text = html.replaceAll("<[^>]*>", "").replaceAll("\\s+", " ").trim();
        return sendEmail(to, subject, text, html);
    }

    private boolean sendEmailWithConfig(EmailConfig config, String to, String subject, String textContent, String htmlContent) {
        try {
            Properties props = createMailProperties(config);
            Session session = createMailSession(props, config);

            Message message = new MimeMessage(session);
            message.setFrom(new InternetAddress(config.getFromEmail(), config.getFromName()));
            message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to));
            message.setSubject(subject);

            if (htmlContent != null && !htmlContent.trim().isEmpty()) {
                // Create multipart message with both text and HTML
                Multipart multipart = new MimeMultipart("alternative");
                
                // Text part
                BodyPart textBodyPart = new MimeBodyPart();
                textBodyPart.setText(textContent != null ? textContent : "");
                multipart.addBodyPart(textBodyPart);
                
                // HTML part
                BodyPart htmlBodyPart = new MimeBodyPart();
                htmlBodyPart.setContent(htmlContent, "text/html; charset=utf-8");
                multipart.addBodyPart(htmlBodyPart);
                
                message.setContent(multipart);
            } else {
                // Plain text only
                message.setText(textContent != null ? textContent : "");
            }

            Transport.send(message);
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    private Properties createMailProperties(EmailConfig config) {
        Properties props = new Properties();
        props.put("mail.smtp.host", config.getSmtpHost());
        props.put("mail.smtp.port", config.getSmtpPort().toString());
        
        if (config.hasAuthentication()) {
            props.put("mail.smtp.auth", "true");
        }
        
        if (Boolean.TRUE.equals(config.getSmtpTls())) {
            props.put("mail.smtp.starttls.enable", "true");
        }
        
        if (Boolean.TRUE.equals(config.getSmtpSsl())) {
            props.put("mail.smtp.ssl.enable", "true");
        }
        
        return props;
    }

    private Session createMailSession(Properties props, EmailConfig config) {
        if (config.hasAuthentication()) {
            return Session.getInstance(props, new Authenticator() {
                @Override
                protected PasswordAuthentication getPasswordAuthentication() {
                    return new PasswordAuthentication(config.getSmtpUsername(), config.getSmtpPassword());
                }
            });
        } else {
            return Session.getInstance(props);
        }
    }

    public CompletionStage<Boolean> testEmailConfiguration() {
        return sendEmail(
            "test@example.com", 
            "Test Email Configuration", 
            "This is a test email to verify SMTP configuration.",
            "<h1>Test Email</h1><p>This is a test email to verify SMTP configuration.</p>"
        );
    }
}