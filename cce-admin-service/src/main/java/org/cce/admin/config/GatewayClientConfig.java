package org.cce.admin.config;

import java.time.Duration;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/** The {@link RestClient} for gateway-service (section 7), with the configured timeouts. */
@Configuration
public class GatewayClientConfig {

    @Bean
    RestClient gatewayRestClient(AdminProperties properties) {
        AdminProperties.Gateway gateway = properties.gateway();
        return gatewayRestClient(gateway.baseUrl(), gateway.connectTimeout(), gateway.readTimeout());
    }

    /** Also used by tests that run the client against a local fake. */
    public static RestClient gatewayRestClient(String baseUrl, Duration connectTimeout, Duration readTimeout) {
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(ClientHttpRequestFactoryBuilder.jdk().build(ClientHttpRequestFactorySettings.defaults()
                        .withConnectTimeout(connectTimeout)
                        .withReadTimeout(readTimeout)))
                .build();
    }
}
