package com.quipmarket.support;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureHttpGraphQlTester;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

/**
 * Full Spring context against a REAL PostgreSQL 16 in Docker (Testcontainers), with Flyway
 * migrations + demo inventory, and a controllable clock frozen at 2026-10-01T12:00Z.
 * All classes using this annotation share one context and one container.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureHttpGraphQlTester
@Import(TestInfrastructure.class)
@TestPropertySource(properties = "spring.flyway.locations=classpath:db/migration,classpath:db/demo")
public @interface IntegrationTest {}
