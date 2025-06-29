package actions;

import models.User;
import play.mvc.*;
import play.libs.Json;
import play.db.jpa.JPAApi;

import jakarta.inject.Inject;
import jakarta.persistence.TypedQuery;
import java.util.concurrent.CompletionStage;
import java.util.Optional;

/**
 * Action to ensure only admin users can access certain endpoints
 */
public class AdminOnly extends Action.Simple {

    @Inject
    private JPAApi jpaApi;

    @Override
    public CompletionStage<Result> call(Http.Request req) {
        Optional<String> usernameOpt = req.session().get("username");
        
        if (usernameOpt.isEmpty()) {
            // User is not logged in
            Result unauthorized = Results.unauthorized(Json.newObject().put("error", "Authentication required"));
            return java.util.concurrent.CompletableFuture.completedFuture(unauthorized);
        }

        String username = usernameOpt.get();
        
        // Check if user has admin role
        try {
            boolean isAdmin = jpaApi.withTransaction(em -> {
                TypedQuery<User> query = em.createQuery(
                    "SELECT u FROM User u WHERE u.username = :username", User.class);
                query.setParameter("username", username);
                
                Optional<User> userOpt = query.getResultStream().findFirst();
                if (userOpt.isPresent()) {
                    User user = userOpt.get();
                    return user.getRoles().contains(User.Role.ADMIN);
                }
                return false;
            });

            if (!isAdmin) {
                Result forbidden = Results.forbidden(Json.newObject().put("error", "Admin access required"));
                return java.util.concurrent.CompletableFuture.completedFuture(forbidden);
            }

            // User is admin, proceed with the original action
            return delegate.call(req);

        } catch (Exception e) {
            play.Logger.error("Error checking admin role for user: " + username, e);
            Result error = Results.internalServerError(Json.newObject().put("error", "Authorization check failed"));
            return java.util.concurrent.CompletableFuture.completedFuture(error);
        }
    }
}