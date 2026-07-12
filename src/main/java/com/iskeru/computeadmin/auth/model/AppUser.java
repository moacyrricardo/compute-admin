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
 * A signed-in user. Self-registers via email + password (the bcrypt hash lives in
 * {@code passwordHash}); every owned entity points its {@code owner} here. Not
 * Envers-audited — user records are not versioned config.
 *
 * <p>spec-011; email+password since spec-014 (replacing the Google subject).
 */
@Entity
@Table(name = "app_user", uniqueConstraints = {
        @UniqueConstraint(name = "uq_app_user_email", columnNames = "email")
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

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
