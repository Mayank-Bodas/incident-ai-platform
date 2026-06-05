package com.incidentplatform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Application Entry Point — The main class that bootstraps Spring Boot.
 *
 * WHY @SpringBootApplication?
 * It's a meta-annotation combining:
 *   - @Configuration: This class is a Spring config source
 *   - @EnableAutoConfiguration: Spring auto-configures beans based on classpath
 *   - @ComponentScan: Scans for @Component, @Service, @Repository in this package
 *
 * WHY @EnableJpaAuditing?
 * Enables @CreatedDate, @LastModifiedDate on entities.
 * Spring automatically populates these fields — no manual timestamp management.
 * Interview: "How do you track when records are created/updated?" → JPA Auditing
 *
 * WHY @EnableCaching?
 * Activates Spring's cache abstraction. Without this, @Cacheable annotations do nothing.
 * The actual cache implementation (Redis) is configured in RedisConfig.
 *
 * WHY @EnableAsync?
 * Allows @Async methods to run in a separate thread pool.
 * Used for non-blocking agent invocations.
 */
@SpringBootApplication
@EnableJpaAuditing
@EnableCaching
@EnableAsync
public class IncidentPlatformApplication {

    public static void main(String[] args) {
        SpringApplication.run(IncidentPlatformApplication.class, args);
    }
}
