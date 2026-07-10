package com.iskeru.computeadmin.auth.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * A short-lived MCP self-setup pairing (RFC 8628-style device authorization). The
 * client holds the secret device code (only its hash is stored); a human enters
 * the human-readable {@code userCode} in the UI to approve. On approval a
 * {@link PersonalToken} is minted for that user and its id recorded in
 * {@code issuedTokenId}; the plaintext is handed to the poller exactly once and
 * the request moves to {@link PairingStatus#CONSUMED}.
 *
 * <p>spec-011.
 */
@Entity
@Table(name = "pairing_request", uniqueConstraints = {
        @UniqueConstraint(name = "uq_pairing_device_code_hash", columnNames = "device_code_hash"),
        @UniqueConstraint(name = "uq_pairing_user_code", columnNames = "user_code")
})
@Getter
@Setter
@NoArgsConstructor
public class PairingRequest {

    @Id
    @Column(length = 36)
    private String id = UUID.randomUUID().toString();

    @Column(name = "device_code_hash", nullable = false)
    private String deviceCodeHash;

    @Column(name = "user_code", nullable = false, length = 20)
    private String userCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PairingStatus status = PairingStatus.PENDING;

    @Column(name = "approved_by_user_id", length = 36)
    private String approvedByUserId;

    @Column(name = "issued_token_id", length = 36)
    private String issuedTokenId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;
}
