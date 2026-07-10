package com.iskeru.computeadmin.auth.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * A per-user MCP personal token. Only the {@code tokenHash} is stored — the
 * plaintext is shown once at creation and never persisted. A {@code revokedAt}
 * marks it dead; the MCP filter looks tokens up by hash, rejecting revoked ones.
 *
 * <p>spec-011.
 */
@Entity
@Table(name = "personal_token", uniqueConstraints = {
        @UniqueConstraint(name = "uq_personal_token_hash", columnNames = "token_hash")
})
@Getter
@Setter
@NoArgsConstructor
public class PersonalToken {

    @Id
    @Column(length = 36)
    private String id = UUID.randomUUID().toString();

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_personal_token_owner"))
    private AppUser owner;

    @Column(nullable = false)
    private String label;

    @Column(name = "token_hash", nullable = false)
    private String tokenHash;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;
}
