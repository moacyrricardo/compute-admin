package com.iskeru.computeadmin.common;

import com.iskeru.computeadmin.common.CommonDtos.Health;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the RESTEasy-beside-Spring-Boot seam works end to end: {@code GET
 * /api/health} returns 200 with {@code status=ok}.
 *
 * <p>Runs under the {@code test} profile so the full-context boot uses the
 * in-memory datasource from {@code application-test.yml} rather than the
 * file-based dev DB in {@code application.yml} — keeping the run hermetic. Every
 * later {@code *WebTest} inherits this by declaring {@code @ActiveProfiles("test")}.
 *
 * <p>spec-001.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class HealthWebTest {

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
