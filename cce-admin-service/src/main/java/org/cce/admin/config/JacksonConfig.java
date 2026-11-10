package org.cce.admin.config;

import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Keeps Boot's own {@code ObjectMapper} and adds the trimming string deserializer (section 4.1). */
@Configuration
public class JacksonConfig {

    @Bean
    Jackson2ObjectMapperBuilderCustomizer trimmingStringCustomizer() {
        return builder -> builder.deserializerByType(String.class, new TrimmingStringDeserializer());
    }
}
