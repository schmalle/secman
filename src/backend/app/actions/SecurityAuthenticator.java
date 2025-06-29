package actions;

import play.mvc.Http;
import play.mvc.Result;
import play.mvc.Security;

import java.util.Optional;

/**
 * Custom authenticator to check if a user is logged in by verifying the session.
 * This is for use with @Security.Authenticated annotation.
 */
public class SecurityAuthenticator extends Security.Authenticator {

    @Override
    public Optional<String> getUsername(Http.Request req) {
        // Retrieve username from session
        return req.session().get("username");
    }

    @Override
    public Result onUnauthorized(Http.Request req) {
        // Return unauthorized result with JSON message
        return play.mvc.Results.unauthorized(
            play.libs.Json.newObject().put("error", "Authentication required")
        );
    }
}
