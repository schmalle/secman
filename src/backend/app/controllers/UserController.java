package controllers;

import actions.Secured; // Import Secured action
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.TypedQuery;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import models.User;
import org.mindrot.jbcrypt.BCrypt;
import play.db.jpa.JPAApi;
import play.libs.Json;
import play.mvc.*;

import java.util.EnumSet;
import java.util.Optional;
import java.util.stream.StreamSupport;

@Singleton
@With(Secured.class) // Apply to the whole controller
public class UserController extends Controller {

    private final JPAApi jpaApi;

    @Inject
    public UserController(JPAApi jpaApi) {
        this.jpaApi = jpaApi;
    }

    // GET /api/users
    public Result list(Http.Request request) {
        return jpaApi.withTransaction(em -> {
            TypedQuery<User> query = em.createQuery("SELECT u FROM User u ORDER BY u.username", User.class);
            List<User> users = query.getResultList();
            // Avoid sending password hash to frontend - now handled by @JsonIgnore
            // users.forEach(u -> u.setPasswordHash(null)); 
            return ok(Json.toJson(users));
        });
    }

    // POST /api/users
    public Result create(Http.Request request) {
        JsonNode json = request.body().asJson();
        if (json == null) {
            return badRequest(Json.newObject().put("error", "Expecting JSON data"));
        }

        try {
            User user = Json.fromJson(json, User.class);

            // Validate required fields
            if (user.getUsername() == null || user.getUsername().trim().isEmpty() ||
                user.getEmail() == null || user.getEmail().trim().isEmpty() ||
                json.findPath("password").asText("").trim().isEmpty()) {
                return badRequest(Json.newObject().put("error", "Username, email, and password are required"));
            }

            // Hash the password
            String plainPassword = json.findPath("password").asText();
            user.setPasswordHash(BCrypt.hashpw(plainPassword, BCrypt.gensalt()));

            // Set default role if not provided or handle role from JSON
            JsonNode rolesNode = json.findPath("roles");
            if (rolesNode.isMissingNode() || !rolesNode.isArray() || rolesNode.isEmpty()) {
                 // Default to USER role if not specified
                 user.setRoles(EnumSet.of(User.Role.USER));
            } else {
                 Set<User.Role> roles = StreamSupport.stream(rolesNode.spliterator(), false)
                    .map(JsonNode::asText)
                    .map(String::toUpperCase) // Ensure case-insensitivity
                    .map(User.Role::valueOf)   // Convert string to enum
                    .collect(Collectors.toSet());
                 user.setRoles(roles.isEmpty() ? EnumSet.of(User.Role.USER) : roles); // Default if empty array
            }


            return jpaApi.withTransaction(em -> {
                try {
                    em.persist(user);
                    // user.setPasswordHash(null); // Don't return hash - now handled by @JsonIgnore
                    return created(Json.toJson(user));
                } catch (PersistenceException e) {
                     // Handle potential unique constraint violations (username/email)
                    if (e.getCause() instanceof org.hibernate.exception.ConstraintViolationException) {
                        return badRequest(Json.newObject().put("error", "Username or email already exists."));
                    }
                    // Log other persistence exceptions and return a generic error
                    play.Logger.error("Error persisting user: " + user.getUsername(), e);
                    return internalServerError(Json.newObject().put("error", "Could not create user due to a server error."));
                }
            });
        } catch (IllegalArgumentException e) {
             // Catch errors from Role.valueOf if invalid role string is provided
             return badRequest(Json.newObject().put("error", "Invalid role specified: " + e.getMessage()));
        } catch (Exception e) {
             play.Logger.warn("Error found " + e.getStackTrace());
             play.Logger.warn("Error processing request: " + e.getMessage());
             play.Logger.warn("Error details: " + e);
            return badRequest(Json.newObject().put("error", "Error processing request: " + e.getMessage()));
        }
    }

    // GET /api/users/:id
    public Result get(Http.Request request, Long id) {
        return jpaApi.withTransaction(em -> {
            try {
                User user = em.find(User.class, id);
                if (user == null) {
                    return notFound(Json.newObject().put("error", "User not found"));
                }
                // user.setPasswordHash(null); // Don't return hash - now handled by @JsonIgnore
                return ok(Json.toJson(user));
            } catch (Exception e) {
                play.Logger.error("Error fetching user: " + id, e);
                return internalServerError(Json.newObject().put("error", "Could not retrieve user due to a server error."));
            }
        });
    }

    // PUT /api/users/:id
    public Result update(Http.Request request, Long id) {
        JsonNode json = request.body().asJson();
        if (json == null) {
            return badRequest(Json.newObject().put("error", "Expecting JSON data"));
        }

        try {
            return jpaApi.withTransaction(em -> {
                User existingUser = em.find(User.class, id);
                if (existingUser == null) {
                    return notFound(Json.newObject().put("error", "User not found"));
                }

                // Update fields from JSON
                User updatedData = Json.fromJson(json, User.class);

                if (updatedData.getUsername() != null && !updatedData.getUsername().trim().isEmpty()) {
                    existingUser.setUsername(updatedData.getUsername());
                }
                 if (updatedData.getEmail() != null && !updatedData.getEmail().trim().isEmpty()) {
                    existingUser.setEmail(updatedData.getEmail());
                }

                // Update password only if provided
                String plainPassword = json.findPath("password").asText(null);
                if (plainPassword != null && !plainPassword.trim().isEmpty()) {
                    existingUser.setPasswordHash(BCrypt.hashpw(plainPassword, BCrypt.gensalt()));
                }

                // Update roles if provided
                JsonNode rolesNode = json.findPath("roles");
                 if (rolesNode.isArray() && !rolesNode.isEmpty()) {
                     Set<User.Role> roles = StreamSupport.stream(rolesNode.spliterator(), false)
                         .map(JsonNode::asText)
                         .map(String::toUpperCase)
                         .map(User.Role::valueOf)
                         .collect(Collectors.toSet());
                     existingUser.setRoles(roles);
                 } else if (rolesNode.isArray() && rolesNode.isEmpty()) {
                     // If an empty array is explicitly passed, maybe clear roles or set default?
                     // Here we'll default to USER if roles array is empty in update. Adjust as needed.
                     existingUser.setRoles(EnumSet.of(User.Role.USER));
                 } // If rolesNode is missing, don't update roles


                try {
                    em.merge(existingUser);
                    // existingUser.setPasswordHash(null); // Don't return hash - now handled by @JsonIgnore
                    return ok(Json.toJson(existingUser));
                } catch (PersistenceException e) {
                    if (e.getCause() instanceof org.hibernate.exception.ConstraintViolationException) {
                        return badRequest(Json.newObject().put("error", "Username or email already exists."));
                    }
                    throw e;
                }
            });
        } catch (IllegalArgumentException e) {
             return badRequest(Json.newObject().put("error", "Invalid role specified: " + e.getMessage()));
        } catch (Exception e) {
            play.Logger.warn("Error found " + e.getStackTrace());
            return badRequest(Json.newObject().put("error", "Error processing request: " + e.getMessage()));
        }
    }


    // DELETE /api/users/:id
    public Result delete(Http.Request request, Long id) {
        return jpaApi.withTransaction(em -> {
            User user = em.find(User.class, id);
            if (user == null) {
                return notFound(Json.newObject().put("error", "User not found"));
            }

            try {
                em.remove(user);
                return ok(Json.newObject().put("message", "User deleted successfully"));
            } catch (Exception e) {
                // Log the error server-side
                play.Logger.error("Error deleting user " + id, e);
                return internalServerError(Json.newObject().put("error", "Error deleting user: " + e.getMessage()));
            }
        });
    }
}
