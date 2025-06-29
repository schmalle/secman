package services;

import models.AssessmentToken;
import models.RiskAssessment;
import play.db.jpa.JPAApi;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.persistence.TypedQuery;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Optional;

@Singleton
public class TokenService {

    private final JPAApi jpaApi;
    private final SecureRandom secureRandom;

    @Inject
    public TokenService(JPAApi jpaApi) {
        this.jpaApi = jpaApi;
        this.secureRandom = new SecureRandom();
    }

    public String generateSecureToken() {
        byte[] randomBytes = new byte[32]; // 256 bits
        secureRandom.nextBytes(randomBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
    }

    public AssessmentToken createAssessmentToken(RiskAssessment riskAssessment, String respondentEmail) {
        return jpaApi.withTransaction(em -> {
            // Validate input parameters
            if (riskAssessment == null || riskAssessment.getId() == null) {
                throw new IllegalArgumentException("Risk assessment must be persisted before creating token");
            }
            if (respondentEmail == null || respondentEmail.trim().isEmpty()) {
                throw new IllegalArgumentException("Respondent email cannot be null or empty");
            }
            
            // First try to find existing valid token
            TypedQuery<AssessmentToken> existingQuery = em.createQuery(
                "SELECT t FROM AssessmentToken t WHERE t.riskAssessment.id = :assessmentId AND t.respondentEmail = :email AND t.usedAt IS NULL AND t.expiresAt > :now ORDER BY t.createdAt DESC",
                AssessmentToken.class
            );
            existingQuery.setParameter("assessmentId", riskAssessment.getId());
            existingQuery.setParameter("email", respondentEmail.trim());
            existingQuery.setParameter("now", LocalDateTime.now());
            existingQuery.setMaxResults(1);
            
            try {
                // If valid token exists, return it
                return existingQuery.getSingleResult();
            } catch (Exception e) {
                // No valid token found, create new one
                String token = generateSecureToken();
                LocalDateTime expiresAt = riskAssessment.getEndDate().atTime(23, 59, 59);
                
                // Create and validate token before persisting
                AssessmentToken assessmentToken = new AssessmentToken();
                assessmentToken.setRiskAssessment(riskAssessment);
                assessmentToken.setToken(token);
                assessmentToken.setRespondentEmail(respondentEmail.trim());
                assessmentToken.setExpiresAt(expiresAt);
                
                try {
                    em.persist(assessmentToken);
                    em.flush(); // Force immediate write to detect conflicts
                    return assessmentToken;
                } catch (Exception createException) {
                    // If creation failed due to unique constraint, try to find the existing token again
                    try {
                        return existingQuery.getSingleResult();
                    } catch (Exception findException) {
                        // If we still can't find it, the original error was likely not a duplicate
                        throw new RuntimeException("Failed to create assessment token: " + createException.getMessage(), createException);
                    }
                }
            }
        });
    }

    public Optional<AssessmentToken> validateToken(String token) {
        return jpaApi.withTransaction(em -> {
            TypedQuery<AssessmentToken> query = em.createQuery(
                "SELECT t FROM AssessmentToken t WHERE t.token = :token",
                AssessmentToken.class
            );
            query.setParameter("token", token);
            
            try {
                AssessmentToken assessmentToken = query.getSingleResult();
                if (assessmentToken.isValid()) {
                    return Optional.of(assessmentToken);
                } else {
                    return Optional.empty();
                }
            } catch (Exception e) {
                return Optional.empty();
            }
        });
    }

    public boolean markTokenAsUsed(String token) {
        return jpaApi.withTransaction(em -> {
            TypedQuery<AssessmentToken> query = em.createQuery(
                "SELECT t FROM AssessmentToken t WHERE t.token = :token",
                AssessmentToken.class
            );
            query.setParameter("token", token);
            
            try {
                AssessmentToken assessmentToken = query.getSingleResult();
                if (assessmentToken.isValid()) {
                    assessmentToken.markAsUsed();
                    em.merge(assessmentToken);
                    return true;
                } else {
                    return false;
                }
            } catch (Exception e) {
                return false;
            }
        });
    }

    public void cleanupExpiredTokens() {
        jpaApi.withTransaction(em -> {
            int deletedCount = em.createQuery(
                "DELETE FROM AssessmentToken t WHERE t.expiresAt < :now"
            )
            .setParameter("now", LocalDateTime.now())
            .executeUpdate();
            
            System.out.println("Cleaned up " + deletedCount + " expired tokens");
            return null;
        });
    }

    public Optional<AssessmentToken> getTokenByRiskAssessmentAndEmail(Long riskAssessmentId, String email) {
        return jpaApi.withTransaction(em -> {
            TypedQuery<AssessmentToken> query = em.createQuery(
                "SELECT t FROM AssessmentToken t WHERE t.riskAssessment.id = :assessmentId AND t.respondentEmail = :email AND t.usedAt IS NULL AND t.expiresAt > :now ORDER BY t.createdAt DESC",
                AssessmentToken.class
            );
            query.setParameter("assessmentId", riskAssessmentId);
            query.setParameter("email", email);
            query.setParameter("now", LocalDateTime.now());
            query.setMaxResults(1);
            
            try {
                return Optional.of(query.getSingleResult());
            } catch (Exception e) {
                return Optional.empty();
            }
        });
    }
}