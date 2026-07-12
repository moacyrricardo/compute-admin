package com.iskeru.computeadmin.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * The single {@link PasswordEncoder} bean — a {@link BCryptPasswordEncoder} at the
 * default strength (10) — that {@code AuthService} injects to hash registrations
 * and verify logins. This is {@code spring-security-crypto} used standalone: a
 * crypto jar, not {@code spring-boot-starter-security}, so it installs no filter
 * chain that would fight the app's own {@code JwtScopeFilter}/{@code @Secured}
 * model.
 *
 * <p>spec-014.
 */
@Configuration
public class PasswordEncoderConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
