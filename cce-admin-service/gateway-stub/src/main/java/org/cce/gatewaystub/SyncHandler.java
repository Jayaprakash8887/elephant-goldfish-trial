package org.cce.gatewaystub;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.util.List;
import java.util.Set;

/**
 * {@code POST /api/permissions/sync-to-keycloak} (section 9.1). Checks in this order: in
 * {@code fail} mode, 503 and nothing changes; without an {@code Authorization: Bearer} header, 401,
 * and nothing else about the token is checked; otherwise it applies the
 * {@link RealmRoleSyncPlanner} plan and returns 200 with a JSON summary.
 */
public class SyncHandler implements HttpHandler {

    private final ModeHandler mode;
    private final PermissionStore permissions;
    private final RealmRoles realmRoles;
    private final String realm;
    private final ObjectMapper objectMapper;

    public SyncHandler(ModeHandler mode, PermissionStore permissions, RealmRoles realmRoles, String realm,
            ObjectMapper objectMapper) {
        this.mode = mode;
        this.permissions = permissions;
        this.realmRoles = realmRoles;
        this.realm = realm;
        this.objectMapper = objectMapper;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        exchange.getRequestBody().readAllBytes();
        handle(exchange.getRequestMethod(), exchange.getRequestHeaders().getFirst("Authorization")).send(exchange);
    }

    GatewayStubMain.Reply handle(String method, String authorization) {
        if (!"POST".equals(method)) {
            return json(405, error("Use POST."));
        }
        if (mode.isFailing()) {
            return json(503, error("The stand-in is in fail mode."));
        }
        if (!hasBearer(authorization)) {
            return json(401, error("A bearer token is required."));
        }
        try {
            Set<String> names = permissions.distinctPermissionNames();
            List<RealmRoles.RealmRole> existing = realmRoles.list();
            RealmRoleSyncPlanner.Plan plan = RealmRoleSyncPlanner.plan(names, existing, realm);
            for (String name : plan.toCreate()) {
                realmRoles.create(name);
            }
            for (String name : plan.toDelete()) {
                realmRoles.delete(name);
            }
            ObjectNode summary = objectMapper.createObjectNode();
            summary.putPOJO("created", plan.toCreate());
            summary.putPOJO("deleted", plan.toDelete());
            return json(200, summary);
        } catch (RuntimeException e) {
            System.err.println("Sync failed: " + e);
            return json(500, error("Sync failed: " + e.getMessage()));
        }
    }

    private static boolean hasBearer(String authorization) {
        if (authorization == null) {
            return false;
        }
        String value = authorization.trim();
        return value.regionMatches(true, 0, "Bearer ", 0, 7) && !value.substring(7).isBlank();
    }

    private ObjectNode error(String message) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("error", message);
        return node;
    }

    private GatewayStubMain.Reply json(int status, ObjectNode body) {
        return new GatewayStubMain.Reply(status, "application/json", body.toString());
    }
}
