package org.cce.gatewaystub;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * {@code GET} and {@code PUT /_stub/mode}: the runtime switch between {@code ok} and {@code fail}
 * (AS-REQ-23). The mode lives in memory and starts as {@code ok}. No token is needed. A {@code PUT}
 * with any other body gets 400.
 */
public class ModeHandler implements HttpHandler {

    public static final String OK = "ok";
    public static final String FAIL = "fail";

    private volatile String mode = OK;

    public boolean isFailing() {
        return FAIL.equals(mode);
    }

    public String mode() {
        return mode;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        handle(exchange.getRequestMethod(), body).send(exchange);
    }

    GatewayStubMain.Reply handle(String method, String body) {
        switch (method) {
            case "GET":
                return GatewayStubMain.Reply.text(200, mode);
            case "PUT":
                String requested = body == null ? "" : body.trim();
                if (!OK.equals(requested) && !FAIL.equals(requested)) {
                    return GatewayStubMain.Reply.text(400, "The body must be ok or fail.");
                }
                mode = requested;
                return GatewayStubMain.Reply.text(200, mode);
            default:
                return GatewayStubMain.Reply.text(405, "Use GET or PUT.");
        }
    }
}
