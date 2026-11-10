package org.cce.admin.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.cce.admin.client.SyncResult;
import org.cce.admin.entity.UserEntity;
import org.cce.admin.entity.UserStatus;
import org.cce.admin.repository.UserRepository;
import org.cce.admin.support.DatabaseTestBase;
import org.cce.admin.support.TestJwtFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * AD-19 with the default {@code TRUSTED_PROXIES}, which matches no address: through a real HTTP
 * client and Tomcat, a forwarded header cannot set the audited address.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ClientIpDefaultWebTest extends DatabaseTestBase {

    @LocalServerPort
    private int port;

    @Autowired
    private UserRepository userRepository;

    @Test
    void theAuditedAddressIsTheDirectCaller() throws Exception {
        assertThat(createRoleAndReadIp(port, "IP_DEFAULT_ROLE")).isEqualTo("127.0.0.1");
    }

    /** Sends {@code POST /admin/v1/roles} with {@code X-Forwarded-For: 203.0.113.7} and reads the row's address. */
    private String createRoleAndReadIp(int serverPort, String roleName) throws Exception {
        when(gatewaySyncClient.sync()).thenReturn(new SyncResult(true, 200, null));
        UserEntity admin = new UserEntity();
        admin.setKeycloakId(UUID.randomUUID());
        admin.setUsername("ip-admin");
        admin.setEmail("ip-admin@cce.local");
        admin.setFirstName("Ip");
        admin.setLastName("Admin");
        admin.setStatus(UserStatus.ACTIVE);
        UserEntity saved = userRepository.save(admin);

        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + serverPort + "/admin/v1/roles"))
                .header("Authorization", TestJwtFactory.token().subject(saved.getKeycloakId()).roles("SUPER_ADMIN").bearer())
                .header("Content-Type", "application/json")
                .header("X-Forwarded-For", "203.0.113.7")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"name\":\"" + roleName + "\",\"displayName\":\"" + roleName + "\"}"))
                .build();
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        }
        return jdbcTemplate.queryForObject("SELECT a.ip_address FROM audit_log a JOIN roles r ON r.id = a.entity_id"
                + " WHERE a.action = 'ROLE_CREATED' AND r.name = ?", String.class, roleName);
    }
}
