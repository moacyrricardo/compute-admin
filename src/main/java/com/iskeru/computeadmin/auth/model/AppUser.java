package com.iskeru.computeadmin.auth.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * A signed-in user. First Google login self-registers one (keyed by
 * {@code googleSub}); every owned entity points its {@code owner} here. Not
 * Envers-audited — user records are not versioned config.
 *
 * <p>spec-011.
 */
@Entity
@Table(name = "app_user", uniqueConstraints = {
        @UniqueConstraint(name = "uq_app_user_email", columnNames = "email"),
        @UniqueConstraint(name = "uq_app_user_google_sub", columnNames = "google_sub")
})
@Getter
@Setter
@NoArgsConstructor
public class AppUser {

    @Id
    @Column(length = 36)
    private String id = UUID.randomUUID().toString();

    @Column(nullable = false, length = 320)
    private String email;

    @Column(length = 255)
    private String name;

    @Column(name = "google_sub", nullable = false, length = 255)
    private String googleSub;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
