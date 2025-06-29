package actions;

import play.mvc.*;
import java.util.concurrent.CompletionStage;
import play.libs.Json;

/**
 * Custom action to check if a user is logged in by verifying the session.
 */
public class Secured extends Action.Simple {

    @Override
    public CompletionStage<Result> call(Http.Request req) {
        // Check if username exists in session
        if (req.session().get("username").isEmpty()) {
            // User is not logged in
            Result unauthorized = Results.unauthorized(Json.newObject().put("error", "Authentication required"));
            return java.util.concurrent.CompletableFuture.completedFuture(unauthorized);
        } else {
            // User is logged in, proceed with the original action
            return delegate.call(req);
        }
    }
}
