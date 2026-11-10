package org.cce.admin.client;

import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;
import org.cce.admin.config.AdminProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.RestClient;

/**
 * {@link GatewaySyncClient} over HTTP (section 7, 5.10 steps 1 and 2): {@code POST
 * {GATEWAY_BASE_URL}{GATEWAY_SYNC_PATH}} with the service's own bearer token and no body (GW-1,
 * AD-13). Any 2xx is success. Any other status, a timeout, a connection error or a failure to get
 * the token is a failure. The body is never parsed.
 */
@Component
public class HttpGatewaySyncClient implements GatewaySyncClient {

    /** GW-1: the first 500 characters of the body go into the error summary. */
    static final int BODY_SUMMARY_LIMIT = 500;

    private final RestClient restClient;
    private final String syncPath;
    private final Supplier<String> serviceAccessToken;

    @Autowired
    public HttpGatewaySyncClient(@Qualifier("gatewayRestClient") RestClient restClient, AdminProperties properties,
            @Qualifier("serviceAccessToken") Supplier<String> serviceAccessToken) {
        this(restClient, properties.gateway().syncPath(), serviceAccessToken);
    }

    HttpGatewaySyncClient(RestClient restClient, String syncPath, Supplier<String> serviceAccessToken) {
        this.restClient = restClient;
        this.syncPath = syncPath;
        this.serviceAccessToken = serviceAccessToken;
    }

    @Override
    public SyncResult sync() {
        try {
            String token = serviceAccessToken.get();
            return restClient.post()
                    .uri(syncPath)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        if (response.getStatusCode().is2xxSuccessful()) {
                            return new SyncResult(true, status, null);
                        }
                        String body = StreamUtils.copyToString(response.getBody(), StandardCharsets.UTF_8);
                        // DESIGN-GAP: 5.10 says "the HTTP status and the first 500 characters of the
                        // body" without a format; written as "HTTP <status>: <body, cut to 500>".
                        return new SyncResult(false, status, "HTTP " + status + ": " + truncate(body));
                    });
        } catch (RuntimeException e) {
            return new SyncResult(false, null, e.getClass().getName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage()));
        }
    }

    private static String truncate(String body) {
        return body.length() <= BODY_SUMMARY_LIMIT ? body : body.substring(0, BODY_SUMMARY_LIMIT);
    }
}
