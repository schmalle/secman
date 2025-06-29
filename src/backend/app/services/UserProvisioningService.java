package services;

import com.fasterxml.jackson.databind.JsonNode;
import models.*;
import play.db.jpa.JPAApi;
import play.mvc.Http;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import java.security.SecureRandom;
import java.util.*;

@Singleton
public class UserProvisioningService {

    private final JPAApi jpaApi;
    private final SecureRandom secureRandom = new SecureRandom();

    @Inject
    public UserProvisioningService(JPAApi jpaApi) {
        this.jpaApi = jpaApi;
    }

    /**
     * Find existing user or create new user based on external identity information
     */
    public User findOrCreateUser(IdentityProvider provider, JsonNode userInfo, Http.Request request) {
        return jpaApi.withTransaction(em -> {
            try {
                String externalUserId = userInfo.get("sub").asText();
                String email = userInfo.has("email") ? userInfo.get("email").asText() : null;

                // First, try to find existing external identity
                User existingUser = findUserByExternalIdentity(em, provider, externalUserId);
                if (existingUser != null) {
                    return existingUser;
                }

                // If email is available, try to find user by email
                if (email != null) {
                    User userByEmail = findUserByEmail(em, email);
                    if (userByEmail != null) {
                        // Link existing user to external identity
                        linkUserToExternalIdentity(em, userByEmail, provider, userInfo);
                        return userByEmail;
                    }
                }

                // Check if auto-provisioning is enabled
                if (!provider.getAutoProvision()) {
                    play.Logger.warn("User not found and auto-provisioning is disabled for provider: {}", provider.getName());
                    return null;
                }

                // Create new user
                return createNewUser(em, provider, userInfo, request);

            } catch (Exception e) {
                play.Logger.error("Error in user provisioning", e);
                return null;
            }
        });
    }

    /**
     * Find user by external identity
     */
    private User findUserByExternalIdentity(EntityManager em, IdentityProvider provider, String externalUserId) {
        TypedQuery<User> query = em.createQuery(
            "SELECT uei.user FROM UserExternalIdentity uei WHERE uei.provider.id = :providerId AND uei.externalUserId = :externalUserId",
            User.class);
        query.setParameter("providerId", provider.getId());
        query.setParameter("externalUserId", externalUserId);
        
        return query.getResultStream().findFirst().orElse(null);
    }

    /**
     * Find user by email address
     */
    private User findUserByEmail(EntityManager em, String email) {
        TypedQuery<User> query = em.createQuery(
            "SELECT u FROM User u WHERE u.email = :email", User.class);
        query.setParameter("email", email);
        
        return query.getResultStream().findFirst().orElse(null);
    }

    /**
     * Link existing user to external identity
     */
    private void linkUserToExternalIdentity(EntityManager em, User user, IdentityProvider provider, JsonNode userInfo) {
        String externalUserId = userInfo.get("sub").asText();
        
        // Check if link already exists
        TypedQuery<UserExternalIdentity> query = em.createQuery(
            "SELECT uei FROM UserExternalIdentity uei WHERE uei.user.id = :userId AND uei.provider.id = :providerId",
            UserExternalIdentity.class);
        query.setParameter("userId", user.getId());
        query.setParameter("providerId", provider.getId());
        
        Optional<UserExternalIdentity> existingLink = query.getResultStream().findFirst();
        if (existingLink.isEmpty()) {
            UserExternalIdentity externalIdentity = new UserExternalIdentity(user, provider, externalUserId);
            updateExternalIdentityFromUserInfo(externalIdentity, userInfo);
            em.persist(externalIdentity);
            
            play.Logger.info("Linked existing user {} to external provider {}", user.getUsername(), provider.getName());
        }
    }

    /**
     * Create new user from external identity information
     */
    private User createNewUser(EntityManager em, IdentityProvider provider, JsonNode userInfo, Http.Request request) {
        try {
            String email = userInfo.has("email") ? userInfo.get("email").asText() : null;
            String firstName = userInfo.has("given_name") ? userInfo.get("given_name").asText() : null;
            String lastName = userInfo.has("family_name") ? userInfo.get("family_name").asText() : null;
            String displayName = userInfo.has("name") ? userInfo.get("name").asText() : null;

            // Generate username from email or display name
            String username = generateUsername(em, email, displayName, firstName, lastName);
            
            // Create user
            User user = new User();
            user.setUsername(username);
            user.setEmail(email);

            // Set a random password hash (user will authenticate via external provider)
            user.setPasswordHash(generateRandomPasswordHash());

            // Map roles from provider configuration
            Set<User.Role> roles = mapRolesFromProvider(provider, userInfo);
            user.setRoles(roles);

            em.persist(user);
            em.flush(); // Ensure user gets an ID

            // Create external identity link
            String externalUserId = userInfo.get("sub").asText();
            UserExternalIdentity externalIdentity = new UserExternalIdentity(user, provider, externalUserId);
            updateExternalIdentityFromUserInfo(externalIdentity, userInfo);
            em.persist(externalIdentity);

            // Log user provisioning event
            AuthAuditLog auditLog = AuthAuditLog.userProvisioned(user, provider, 
                request.remoteAddress(), request.getHeaders().get("User-Agent").orElse(null));
            em.persist(auditLog);

            play.Logger.info("Auto-provisioned new user {} from external provider {}", username, provider.getName());
            return user;

        } catch (Exception e) {
            play.Logger.error("Error creating new user from external identity", e);
            throw new RuntimeException("User creation failed", e);
        }
    }

    /**
     * Generate unique username
     */
    private String generateUsername(EntityManager em, String email, String displayName, String firstName, String lastName) {
        String baseUsername = null;

        // Try to extract username from email
        if (email != null && email.contains("@")) {
            baseUsername = email.substring(0, email.indexOf("@"));
        }
        // Try to use display name
        else if (displayName != null) {
            baseUsername = displayName.toLowerCase().replaceAll("[^a-z0-9]", "");
        }
        // Try to combine first and last name
        else if (firstName != null && lastName != null) {
            baseUsername = (firstName + lastName).toLowerCase().replaceAll("[^a-z0-9]", "");
        }
        // Fallback to random username
        else {
            baseUsername = "user" + System.currentTimeMillis();
        }

        // Clean username
        baseUsername = baseUsername.toLowerCase().replaceAll("[^a-z0-9]", "");
        if (baseUsername.length() > 20) {
            baseUsername = baseUsername.substring(0, 20);
        }
        if (baseUsername.length() < 3) {
            baseUsername = "user" + System.currentTimeMillis();
        }

        // Ensure username is unique
        String username = baseUsername;
        int counter = 1;
        
        while (isUsernameTaken(em, username)) {
            username = baseUsername + counter;
            counter++;
            if (counter > 1000) {
                // Fallback to timestamp-based username
                username = "user" + System.currentTimeMillis();
                break;
            }
        }

        return username;
    }

    /**
     * Check if username is already taken
     */
    private boolean isUsernameTaken(EntityManager em, String username) {
        TypedQuery<Long> query = em.createQuery(
            "SELECT COUNT(u) FROM User u WHERE u.username = :username", Long.class);
        query.setParameter("username", username);
        return query.getSingleResult() > 0;
    }

    /**
     * Map roles from provider configuration and user claims
     */
    private Set<User.Role> mapRolesFromProvider(IdentityProvider provider, JsonNode userInfo) {
        Set<User.Role> roles = new HashSet<>();
        
        // Default role for auto-provisioned users
        roles.add(User.Role.USER);

        try {
            Map<String, String> roleMapping = provider.getRoleMapping();
            
            // Check role mapping configuration
            for (Map.Entry<String, String> mapping : roleMapping.entrySet()) {
                String claimPath = mapping.getKey();
                String expectedValue = mapping.getValue();
                
                // Extract claim value from user info
                String claimValue = extractClaimValue(userInfo, claimPath);
                
                if (expectedValue.equals(claimValue)) {
                    // Map to admin role if configured
                    if (claimValue.contains("admin") || claimValue.contains("administrator")) {
                        roles.add(User.Role.ADMIN);
                        break;
                    }
                }
            }

            // Check for common admin indicators
            if (userInfo.has("groups")) {
                JsonNode groups = userInfo.get("groups");
                if (groups.isArray()) {
                    for (JsonNode group : groups) {
                        String groupName = group.asText().toLowerCase();
                        if (groupName.contains("admin") || groupName.contains("administrator")) {
                            roles.add(User.Role.ADMIN);
                            break;
                        }
                    }
                }
            }

            // Check email domain for admin privileges (configurable)
            if (userInfo.has("email")) {
                String email = userInfo.get("email").asText();
                // Could be configured per provider, for now just basic check
                if (email.contains("admin@") || email.endsWith("@admin.local")) {
                    roles.add(User.Role.ADMIN);
                }
            }

        } catch (Exception e) {
            play.Logger.error("Error mapping roles from provider", e);
        }

        return roles;
    }

    /**
     * Extract claim value from user info using dot notation path
     */
    private String extractClaimValue(JsonNode userInfo, String claimPath) {
        try {
            String[] pathParts = claimPath.split("\\.");
            JsonNode current = userInfo;
            
            for (String part : pathParts) {
                if (current.has(part)) {
                    current = current.get(part);
                } else {
                    return null;
                }
            }
            
            return current.asText();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Update external identity with user info from provider
     */
    private void updateExternalIdentityFromUserInfo(UserExternalIdentity externalIdentity, JsonNode userInfo) {
        if (userInfo.has("email")) {
            externalIdentity.setEmail(userInfo.get("email").asText());
        }
        if (userInfo.has("name")) {
            externalIdentity.setDisplayName(userInfo.get("name").asText());
        }
        if (userInfo.has("given_name")) {
            externalIdentity.setFirstName(userInfo.get("given_name").asText());
        }
        if (userInfo.has("family_name")) {
            externalIdentity.setLastName(userInfo.get("family_name").asText());
        }
    }

    /**
     * Generate random password hash for externally authenticated users
     */
    private String generateRandomPasswordHash() {
        byte[] randomBytes = new byte[32];
        secureRandom.nextBytes(randomBytes);
        String randomPassword = Base64.getEncoder().encodeToString(randomBytes);
        
        // Use BCrypt to hash the random password
        return org.mindrot.jbcrypt.BCrypt.hashpw(randomPassword, org.mindrot.jbcrypt.BCrypt.gensalt());
    }

    /**
     * Update user information from external provider
     */
    public void updateUserFromProvider(User user, IdentityProvider provider, JsonNode userInfo) {
        jpaApi.withTransaction(em -> {
            try {
                User managedUser = em.find(User.class, user.getId());
                if (managedUser == null) {
                    return null;
                }

                // Update email if changed and email claim mapping is configured
                Map<String, String> claimMappings = provider.getClaimMappings();
                if (claimMappings.containsKey("email") && userInfo.has("email")) {
                    String newEmail = userInfo.get("email").asText();
                    if (!newEmail.equals(managedUser.getEmail())) {
                        managedUser.setEmail(newEmail);
                        play.Logger.info("Updated email for user {} from external provider", managedUser.getUsername());
                    }
                }

                // Update roles if role mapping has changed
                Set<User.Role> newRoles = mapRolesFromProvider(provider, userInfo);
                if (!newRoles.equals(managedUser.getRoles())) {
                    managedUser.setRoles(newRoles);
                    play.Logger.info("Updated roles for user {} from external provider", managedUser.getUsername());
                }

                em.merge(managedUser);
                return null;
            } catch (Exception e) {
                play.Logger.error("Error updating user from provider", e);
                return null;
            }
        });
    }
}