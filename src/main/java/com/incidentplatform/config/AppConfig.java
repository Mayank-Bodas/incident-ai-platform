package com.incidentplatform.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.web.config.EnableSpringDataWebSupport;

import java.util.List;

/**
 * AppConfig — General application configuration beans.
 *
 * WHY EnableSpringDataWebSupport?
 * Enables Pageable argument resolution in controllers.
 * Without this: @PageableDefault and Pageable params in controllers don't work.
 * SERIALIZATION_VIA_DTO = serialize Page objects as clean DTOs (not Spring internals).
 * This is the Spring Boot 3.3+ best practice for Page serialization.
 */
@Configuration
@EnableSpringDataWebSupport(
    pageSerializationMode = EnableSpringDataWebSupport.PageSerializationMode.VIA_DTO
)
public class AppConfig {

    @Bean
    public OpenAPI incidentPlatformOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("AI-Powered Incident Management Platform")
                        .description("""
                            Enterprise-grade platform that automatically investigates production incidents
                            using a multi-agent AI system. Processes alerts, performs root cause analysis,
                            and generates remediation recommendations.
                            """)
                        .version("1.0.0")
                        .contact(new Contact()
                                .name("Platform Engineering Team")
                                .email("platform@incidentplatform.com")))
                .servers(List.of(
                        new Server().url("http://localhost:8080").description("Local Development")
                ));
    }
}
