package com.iskeru.computeadmin.auth;

import com.iskeru.computeadmin.auth.api.AuthDtos;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Email+password auth over the real HTTP surface (spec-014): register mints a JWT
 * and user; a duplicate register is 409; login mints a JWT; a wrong password and an
 * unknown email both return 401 with an identical body; and a registered user's JWT
 * authorizes a {@code @Secured} call ({@code GET /api/tokens}).
 *
 * <p>spec-014.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class AuthWebTest {

    @Autowired
    private TestRestTemplate rest;

    @Test
    void register_MintsJwtAndUser() {
        ResponseEntity<AuthDtos.Session> response = rest.postForEntity(
                "/api/auth/register",
                new AuthDtos.RegisterRequest("web-reg@example.com", "password-123", "Web Reg"),
                AuthDtos.Session.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().token()).isNotBlank();
        assertThat(response.getBody().user().email()).isEqualTo("web-reg@example.com");
        assertThat(response.getBody().user().name()).isEqualTo("Web Reg");
    }

    @Test
    void register_DuplicateEmail_Returns409() {
        rest.postForEntity("/api/auth/register",
                new AuthDtos.RegisterRequest("web-dup@example.com", "password-123", null),
                AuthDtos.Session.class);

        ResponseEntity<String> dup = rest.postForEntity("/api/auth/register",
                new AuthDtos.RegisterRequest("web-dup@example.com", "password-456", null),
                String.class);

        assertThat(dup.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(dup.getBody()).contains("email_taken");
    }

    @Test
    void login_CorrectPassword_MintsJwt() {
        rest.postForEntity("/api/auth/register",
                new AuthDtos.RegisterRequest("web-login@example.com", "password-123", null),
                AuthDtos.Session.class);

        ResponseEntity<AuthDtos.Session> response = rest.postForEntity(
                "/api/auth/login",
                new AuthDtos.LoginRequest("web-login@example.com", "password-123"),
                AuthDtos.Session.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().token()).isNotBlank();
    }

    @Test
    void login_IgnoresUnknownField_StillSignsIn() {
        // Regression (found in live testing): the UI login form sent a `name` field to
        // /auth/login; a stray/unknown property must never fail sign-in — it 400'd before.
        rest.postForEntity("/api/auth/register",
                new AuthDtos.RegisterRequest("web-extra@example.com", "password-123", null),
                AuthDtos.Session.class);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String bodyWithExtraField =
                "{\"email\":\"web-extra@example.com\",\"password\":\"password-123\",\"name\":\"ignored\"}";
        ResponseEntity<AuthDtos.Session> response = rest.postForEntity(
                "/api/auth/login", new HttpEntity<>(bodyWithExtraField, headers), AuthDtos.Session.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().token()).isNotBlank();
    }

    @Test
    void login_WrongPasswordAndUnknownEmail_Return401WithIdenticalBody() {
        rest.postForEntity("/api/auth/register",
                new AuthDtos.RegisterRequest("web-bad@example.com", "password-123", null),
                AuthDtos.Session.class);

        ResponseEntity<String> wrongPassword = rest.postForEntity("/api/auth/login",
                new AuthDtos.LoginRequest("web-bad@example.com", "not-the-password"), String.class);
        ResponseEntity<String> unknownEmail = rest.postForEntity("/api/auth/login",
                new AuthDtos.LoginRequest("web-nobody@example.com", "not-the-password"), String.class);

        assertThat(wrongPassword.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(unknownEmail.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(wrongPassword.getBody()).isEqualTo(unknownEmail.getBody());
    }

    @Test
    void registeredJwt_AuthorizesSecuredCall() {
        AuthDtos.Session session = rest.postForObject(
                "/api/auth/register",
                new AuthDtos.RegisterRequest("web-secured@example.com", "password-123", null),
                AuthDtos.Session.class);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(session.token());
        ResponseEntity<String> tokens = rest.exchange(
                "/api/tokens", HttpMethod.GET, new HttpEntity<>(headers), String.class);

        assertThat(tokens.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
