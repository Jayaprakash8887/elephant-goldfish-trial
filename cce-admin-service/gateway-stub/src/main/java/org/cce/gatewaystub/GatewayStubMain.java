package org.cce.gatewaystub;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

/**
 * The local stand-in for gateway-service's sync endpoint (AD-11). It is not the gateway. Routes:
 * {@code POST /api/permissions/sync-to-keycloak} and {@code GET}/{@code PUT /_stub/mode}.
 */
public final class GatewayStubMain {

    /** One HTTP response. */
    record Reply(int status, String contentType, String body) {

        static Reply text(int status, String body) {
            return new Reply(status, "text/plain; charset=utf-8", body);
        }

        void send(HttpExchange exchange) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
    }

    private GatewayStubMain() {
    }

    public static void main(String[] args) throws IOException {
        StubConfig config = StubConfig.fromEnvironment(System.getenv());
        ObjectMapper objectMapper = new ObjectMapper();
        ModeHandler mode = new ModeHandler();
        SyncHandler sync = new SyncHandler(mode,
                new JdbcPermissionStore(config.databaseUrl(), config.databaseUsername(), config.databasePassword()),
                new HttpRealmRoles(config, objectMapper), config.keycloakRealm(), objectMapper);

        HttpServer server = HttpServer.create(new InetSocketAddress(config.port()), 0);
        server.createContext("/api/permissions/sync-to-keycloak", exchange -> exact(exchange,
                "/api/permissions/sync-to-keycloak", () -> sync.handle(exchange)));
        server.createContext("/_stub/mode", exchange -> exact(exchange, "/_stub/mode", () -> mode.handle(exchange)));
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();
        System.out.println("cce-gateway-stub (a stand-in, not gateway-service) listening on port " + config.port()
                + ", mode " + mode.mode());
    }

    @FunctionalInterface
    private interface IoAction {
        void run() throws IOException;
    }

    /** HttpServer contexts match by prefix; only the exact path is a route. */
    private static void exact(HttpExchange exchange, String path, IoAction action) throws IOException {
        try (exchange) {
            if (!path.equals(exchange.getRequestURI().getPath())) {
                Reply.text(404, "Not found.").send(exchange);
                return;
            }
            action.run();
        }
    }
}
