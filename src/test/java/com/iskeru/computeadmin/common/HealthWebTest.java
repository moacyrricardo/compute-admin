package com.iskeru.computeadmin.common;

import com.iskeru.computeadmin.common.CommonDtos.Health;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the RESTEasy-beside-Spring-Boot seam works end to end: {@code GET
 * /api/health} returns 200 with {@code status=ok}.
 *
 * <p>spec-001.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HealthWebTest {

    /**
     * Isolate the test from the file-based dev datasource in {@code application.yml}
     * so the run neither creates nor pollutes {@code ./data/compute-admin} and stays
     * repeatable. Overrides only the URL; the rest of the config (Flyway, JPA, Envers)
     * still applies.
     */
    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> "jdbc:h2:mem:compute-admin-test;DB_CLOSE_DELAY=-1");
    }

    @Autowired
    private TestRestTemplate rest;

    @Test
    void health_WhenCalled_Returns200AndStatusOk() {
        ResponseEntity<Health> response = rest.getForEntity("/api/health", Health.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo("ok");
    }
}
