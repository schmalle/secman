package controllers;

import com.fasterxml.jackson.databind.JsonNode;
import models.User;
import org.mindrot.jbcrypt.BCrypt;
import play.db.jpa.JPAApi;
import play.libs.Json;
import play.mvc.*;
import play.mvc.Results;

import jakarta.inject.Inject;
import jakarta.persistence.NoResultException;
import jakarta.persistence.TypedQuery;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;


public class AuthController extends Controller {

    private final JPAApi jpaApi;

    @Inject
    public AuthController(JPAApi jpaApi) {
        this.jpaApi = jpaApi;
    }

    public Result login(Http.Request request) {
        JsonNode json = request.body().asJson();
        if (json == null) {
            return Results.badRequest(Json.newObject().put("error", "Expecting Json data"));
        }

        String username = json.findPath("username").textValue();
        String password = json.findPath("password").textValue();

        if (username == null || password == null) {
            return Results.badRequest(Json.newObject().put("error", "Missing username or password"));
        }

        // Wrap database logic in withTransaction
        return jpaApi.withTransaction(em -> {
            TypedQuery<User> query = em.createQuery(
                    "SELECT u FROM User u WHERE u.username = :username", User.class);
            query.setParameter("username", username);

            try {
                User user = query.getSingleResult();
                if (BCrypt.checkpw(password, user.getPasswordHash())) {
                    // Password matches, store username in session and return user info (without password hash)
                    
                    System.out.println("User found: " + username); // Debug print
                    
                    // Create a JSON object manually to exclude password hash
                    com.fasterxml.jackson.databind.node.ObjectNode userJson = Json.newObject();
                    userJson.put("id", user.getId());
                    userJson.put("username", user.getUsername());
                    userJson.put("email", user.getEmail());
                    // Convert roles to a JSON array
                    com.fasterxml.jackson.databind.node.ArrayNode rolesArray = Json.newArray();
                    user.getRoles().stream().map(Enum::name).forEach(rolesArray::add);
                    userJson.set("roles", rolesArray);

                    // Get roles for session storage
                    Set<String> roles = user.getRoles().stream()
                                            .map(Enum::name)
                                            .collect(Collectors.toSet());
                                            
                    // Return the manually created JSON, DO NOT return Json.toJson(user)
                    return Results.ok(userJson) 
                            .addingToSession(request, "username", user.getUsername())
                            .addingToSession(request, "roles", String.join(",", roles)); // Store roles as comma-separated string
                } else {
                    // Password mismatch
                    return Results.unauthorized(Json.newObject().put("error", "Invalid credentials"));
                }
            } catch (NoResultException e) {
                // User not found
                System.out.println("User not found: " + username);
                return Results.unauthorized(Json.newObject().put("error", "Invalid credentials"));
            } catch (Exception e) {
                // Log other exceptions that might occur within the transaction
                play.Logger.error("Login error for user: " + username, e);
                return Results.internalServerError(Json.newObject().put("error", "Login failed due to server error"));
            }
        });
    }

    public Result logout(Http.Request request) {
        return Results.ok(Json.newObject().put("message", "Logged out successfully"))
                .removingFromSession(request, "username", "roles");
    }

    // Check if user is logged in based on session
    // Removed @play.db.jpa.Transactional(readOnly = true)
    public Result status(Http.Request request) {
        Optional<String> usernameOpt = request.session().get("username");
        if (usernameOpt.isPresent()) {
            // Wrap database logic in withTransaction
            return jpaApi.withTransaction(em -> {
                 try {
                    TypedQuery<User> query = em.createQuery(
                            "SELECT u FROM User u WHERE u.username = :username", User.class);
                    query.setParameter("username", usernameOpt.get());
                    User user = query.getSingleResult();
                    
                    // Create a JSON object manually to exclude password hash
                    com.fasterxml.jackson.databind.node.ObjectNode userJson = Json.newObject();
                    userJson.put("id", user.getId());
                    userJson.put("username", user.getUsername());
                    userJson.put("email", user.getEmail());
                    // Convert roles to a JSON array
                    com.fasterxml.jackson.databind.node.ArrayNode rolesArray = Json.newArray();
                    user.getRoles().stream().map(Enum::name).forEach(rolesArray::add);
                    userJson.set("roles", rolesArray);

                    // Return the manually created JSON, DO NOT return Json.toJson(user)
                    return Results.ok(userJson); 
                } catch (NoResultException e) {
                     // User in session but not DB? Clear session.
                     // Note: Cannot modify session inside withTransaction easily, 
                     // consider alternative approach if session clearing is critical here.
                     // For now, just return unauthorized.
                     return Results.unauthorized(Json.newObject().put("error", "Session invalid"));
                } catch (Exception e) {
                     play.Logger.error("Auth status error for user: " + usernameOpt.get(), e);
                     return Results.internalServerError(Json.newObject().put("error", "Could not verify auth status"));
                }
            });
        } else {
            return Results.unauthorized(Json.newObject().put("error", "Not logged in"));
        }
    }
}
