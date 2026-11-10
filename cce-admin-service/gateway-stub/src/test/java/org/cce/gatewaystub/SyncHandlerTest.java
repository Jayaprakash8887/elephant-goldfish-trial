package org.cce.gatewaystub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 503, 401 and 200 from the sync handler, with in-memory stores. */
class SyncHandlerTest {

    private static final class InMemoryPermissions implements PermissionStore {
        final Set<String> names = new TreeSet<>();

        @Override
        public Set<String> distinctPermissionNames() {
            return Set.copyOf(names);
        }
    }

    private static final class InMemoryRealmRoles implements RealmRoles {
        final Map<String, RealmRole> roles = new LinkedHashMap<>();
        final List<String> calls = new ArrayList<>();

        void put(String name, boolean managed) {
            roles.put(name, new RealmRole(name, managed ? Map.of(MANAGED_ATTRIBUTE, List.of("true")) : Map.of()));
        }

        @Override
        public List<RealmRole> list() {
            calls.add("list");
            return List.copyOf(roles.values());
        }

        @Override
        public void create(String name) {
            calls.add("create " + name);
            put(name, true);
        }

        @Override
        public void delete(String name) {
            calls.add("delete " + name);
            roles.remove(name);
        }
    }

    private final ObjectMapper objectMapper = new ObjectMapper();
    private ModeHandler mode;
    private InMemoryPermissions permissions;
    private InMemoryRealmRoles realmRoles;
    private SyncHandler handler;

    @BeforeEach
    void setUp() {
        mode = new ModeHandler();
        permissions = new InMemoryPermissions();
        realmRoles = new InMemoryRealmRoles();
        realmRoles.put("default-roles-cce", false);
        realmRoles.put("offline_access", false);
        realmRoles.put("uma_authorization", false);
        realmRoles.put("SUPER_ADMIN", false);
        realmRoles.put("CCE_ADMIN_SERVICE", false);
        permissions.names.addAll(List.of("SUPER_ADMIN", "CCE_ADMIN_SERVICE"));
        handler = new SyncHandler(mode, permissions, realmRoles, "cce", objectMapper);
    }

    @Test
    void failModeReturns503AndChangesNothingEvenWithoutAToken() {
        mode.handle("PUT", "fail");
        permissions.names.add("NEW_ROLE");

        assertEquals(503, handler.handle("POST", null).status());
        assertEquals(503, handler.handle("POST", "Bearer abc").status());
        assertTrue(realmRoles.calls.isEmpty());
        assertTrue(!realmRoles.roles.containsKey("NEW_ROLE"));
    }

    @Test
    void withoutABearerHeaderReturns401AndChangesNothing() {
        permissions.names.add("NEW_ROLE");

        assertEquals(401, handler.handle("POST", null).status());
        assertEquals(401, handler.handle("POST", "Basic dXNlcjpwYXNz").status());
        assertEquals(401, handler.handle("POST", "Bearer ").status());
        assertTrue(realmRoles.calls.isEmpty());
    }

    @Test
    void withABearerHeaderAppliesThePlanAndReturns200WithASummary() throws Exception {
        permissions.names.add("NEW_ROLE");
        realmRoles.put("OLD_MANAGED", true);
        realmRoles.put("HAND_MADE", false);

        GatewayStubMain.Reply reply = handler.handle("POST", "Bearer any-token-at-all");

        assertEquals(200, reply.status());
        assertEquals("application/json", reply.contentType());
        JsonNode body = objectMapper.readTree(reply.body());
        assertEquals("NEW_ROLE", body.path("created").get(0).asText());
        assertEquals("OLD_MANAGED", body.path("deleted").get(0).asText());
        assertEquals(List.of("list", "create NEW_ROLE", "delete OLD_MANAGED"), realmRoles.calls);
        assertTrue(realmRoles.roles.get("NEW_ROLE").managed());
        assertTrue(realmRoles.roles.containsKey("HAND_MADE"));
        assertTrue(realmRoles.roles.containsKey("SUPER_ADMIN"));
        assertTrue(realmRoles.roles.containsKey("default-roles-cce"));
    }

    @Test
    void afterSwitchingBackToOkTheSyncRunsAgain() {
        mode.handle("PUT", "fail");
        assertEquals(503, handler.handle("POST", "Bearer t").status());
        mode.handle("PUT", "ok");
        assertEquals(200, handler.handle("POST", "Bearer t").status());
    }

    @Test
    void otherMethodsGet405() {
        assertEquals(405, handler.handle("GET", "Bearer t").status());
    }
}
