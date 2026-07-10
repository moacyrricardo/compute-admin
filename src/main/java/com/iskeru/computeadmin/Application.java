package com.iskeru.computeadmin;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * compute-admin entry point.
 *
 * <p>spec-001: buildable Spring Boot base — RESTEasy JAX-RS dispatcher, H2 file
 * DB with Flyway-owned schema, Envers configured but auditing nothing yet.
 */
@SpringBootApplication
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
