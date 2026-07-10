-- spec-011: user accounts, personal tokens, and MCP self-setup pairing.
-- H2 dialect. These tables are not Envers-audited (user/session records are not
-- versioned config), so no _aud tables are created here.

CREATE TABLE app_user (
    id         VARCHAR(36)  NOT NULL,
    email      VARCHAR(320) NOT NULL,
    name       VARCHAR(255),
    google_sub VARCHAR(255) NOT NULL,
    created_at TIMESTAMP     NOT NULL,
    CONSTRAINT pk_app_user PRIMARY KEY (id),
    CONSTRAINT uq_app_user_email UNIQUE (email),
    CONSTRAINT uq_app_user_google_sub UNIQUE (google_sub)
);

CREATE TABLE personal_token (
    id           VARCHAR(36)  NOT NULL,
    owner_id     VARCHAR(36)  NOT NULL,
    label        VARCHAR(255) NOT NULL,
    token_hash   VARCHAR(64)  NOT NULL,
    created_at   TIMESTAMP     NOT NULL,
    last_used_at TIMESTAMP,
    revoked_at   TIMESTAMP,
    CONSTRAINT pk_personal_token PRIMARY KEY (id),
    CONSTRAINT uq_personal_token_hash UNIQUE (token_hash),
    CONSTRAINT fk_personal_token_owner FOREIGN KEY (owner_id) REFERENCES app_user (id)
);

CREATE INDEX idx_personal_token_owner ON personal_token (owner_id);

CREATE TABLE pairing_request (
    id                  VARCHAR(36) NOT NULL,
    device_code_hash    VARCHAR(64) NOT NULL,
    user_code           VARCHAR(20) NOT NULL,
    status              VARCHAR(20) NOT NULL,
    approved_by_user_id VARCHAR(36),
    issued_token_id     VARCHAR(36),
    created_at          TIMESTAMP    NOT NULL,
    expires_at          TIMESTAMP    NOT NULL,
    CONSTRAINT pk_pairing_request PRIMARY KEY (id),
    CONSTRAINT uq_pairing_device_code_hash UNIQUE (device_code_hash),
    CONSTRAINT uq_pairing_user_code UNIQUE (user_code)
);
