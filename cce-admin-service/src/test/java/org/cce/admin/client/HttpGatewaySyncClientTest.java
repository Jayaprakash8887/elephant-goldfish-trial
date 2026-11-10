package org.cce.admin.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.cce.admin.config.GatewayClientConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/** The sync client of section 7 against a JDK {@link HttpServer} fake (5.10 steps 1 and 2, GW-1). */
class HttpGatewaySyncClientTest {

    private static final String SYNC_PATH = "/api/permissions/sync-to-keycloak";

    private HttpServer server;
    private final AtomicReference<Integer> status = new AtomicReference<>(200);
    private final AtomicReference<String> responseBody = new AtomicReference<>("{\"ok\":true}");
    private final AtomicReference<Long> delayMillis = new AtomicReference<>(0L);
    private final AtomicReference<String> seenMethod = new AtomicReference<>();
    private final AtomicReference<String> seenPath = new AtomicReference<>();
    private final AtomicReference<String> seenAuthorization = new AtomicReference<>();
    private final AtomicReference<byte[]> seenBody = new AtomicReference<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        seenMethod.set(exchange.getRequestMethod());
        seenPath.set(exchange.getRequestURI().getPath());
        seenAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
        seenBody.set(exchange.getRequestBody().readAllBytes());
        try {
            Thread.sleep(delayMillis.get());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        byte[] body = responseBody.get().getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status.get(), body.length == 0 ? -1 : body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        } catch (IOException ignored) {
            // The client may have given up after its read timeout.
        }
    }

    private HttpGatewaySyncClient client(Duration readTimeout, Supplier<String> token) {
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        RestClient restClient = GatewayClientConfig.gatewayRestClient(baseUrl, Duration.ofSeconds(2), readTimeout);
        return new HttpGatewaySyncClient(restClient, SYNC_PATH, token);
    }

    private HttpGatewaySyncClient client() {
        return client(Duration.ofSeconds(5), () -> "service-token");
    }

    @Test
    void sendsAPostWithTheBearerTokenToTheSyncPathAndNoBody() {
        SyncResult result = client().sync();

        assertThat(seenMethod.get()).isEqualTo("POST");
        assertThat(seenPath.get()).isEqualTo(SYNC_PATH);
        assertThat(seenAuthorization.get()).isEqualTo("Bearer service-token");
        assertThat(seenBody.get()).isEmpty();
        assertThat(result).isEqualTo(new SyncResult(true, 200, null));
    }

    @Test
    void anyTwoHundredStatusIsSuccess() {
        status.set(204);
        responseBody.set("");
        assertThat(client().sync()).isEqualTo(new SyncResult(true, 204, null));

        status.set(202);
        responseBody.set("not json at all");
        assertThat(client().sync()).isEqualTo(new SyncResult(true, 202, null));
    }

    @Test
    void aClientErrorIsAFailureWithTheStatusAndTheBody() {
        status.set(403);
        responseBody.set("forbidden");

        SyncResult result = client().sync();

        assertThat(result.success()).isFalse();
        assertThat(result.httpStatus()).isEqualTo(403);
        assertThat(result.errorSummary()).isEqualTo("HTTP 403: forbidden");
    }

    @Test
    void aServerErrorIsAFailureWithTheBodyCutTo500Characters() {
        status.set(503);
        String longBody = "x".repeat(400) + "y".repeat(400);
        responseBody.set(longBody);

        SyncResult result = client().sync();

        assertThat(result.success()).isFalse();
        assertThat(result.httpStatus()).isEqualTo(503);
        assertThat(result.errorSummary()).isEqualTo("HTTP 503: " + longBody.substring(0, 500));
        assertThat(result.errorSummary().substring("HTTP 503: ".length())).hasSize(500);
    }

    @Test
    void aTimeoutIsAFailureWithTheExceptionClassAndMessage() {
        delayMillis.set(2_000L);

        SyncResult result = client(Duration.ofMillis(300), () -> "service-token").sync();

        assertThat(result.success()).isFalse();
        assertThat(result.httpStatus()).isNull();
        assertThat(result.errorSummary()).startsWith("org.springframework.web.client.ResourceAccessException");
    }

    @Test
    void aFailingTokenSupplierIsAFailedSyncAndCallsNothing() {
        SyncResult result = client(Duration.ofSeconds(5), () -> {
            throw new IllegalStateException("no token");
        }).sync();

        assertThat(result.success()).isFalse();
        assertThat(result.httpStatus()).isNull();
        assertThat(result.errorSummary()).isEqualTo("java.lang.IllegalStateException: no token");
        assertThat(seenMethod.get()).isNull();
    }
}
