package controllers;

import play.mvc.*;
import play.libs.Json;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * This controller contains an action to handle HTTP requests
 * to the application's home page.
 */
public class HomeController extends Controller {

    /**
     * An action that renders an HTML page with a welcome message.
     * The configuration in the <code>routes</code> file means that
     * this method will be called when the application receives a
     * <code>GET</code> request with a path of <code>/</code>.
     */
    public Result index(Http.Request request) {
        return ok(views.html.index.render());
    }

    /**
     * Health check endpoint for monitoring and status verification
     */
    public Result health() {
        ObjectNode result = Json.newObject();
        result.put("status", "ok");
        result.put("service", "secman-backend");
        result.put("timestamp", System.currentTimeMillis());
        return ok(result);
    }

}
