-- spec-004: the recipe/action approval gate — the security core.
-- H2 dialect. Adds `recipe` and its runnable `action` (structured argv via
-- `arg_token`, typed param schema via `param_def` + `param_allowed_value`, a
-- `sudo` flag and an approval state), plus the hand-written Envers `_aud` tables
-- for the two @Audited entities. `revinfo` already exists from V3 (spec-003).
--
-- Only `recipe` and `action` are audited (esp. the approval transition). The
-- structural children (arg_token/param_def/param_allowed_value) are @NotAudited —
-- their content is captured indirectly by the action's approved snapshot hash, so
-- no _aud tables are created for them.

CREATE TABLE recipe (
    id                       VARCHAR(36)   NOT NULL,
    machine_id               VARCHAR(36)   NOT NULL,
    name                     VARCHAR(255)  NOT NULL,
    description              VARCHAR(2000),
    type                     VARCHAR(20)   NOT NULL,
    -- Blueprint provenance (spec 010); null for a hand-authored recipe.
    source_blueprint_id      VARCHAR(36),
    source_blueprint_version INTEGER,
    created_at               TIMESTAMP     NOT NULL,
    CONSTRAINT pk_recipe PRIMARY KEY (id),
    CONSTRAINT fk_recipe_machine FOREIGN KEY (machine_id) REFERENCES machine (id)
);

CREATE INDEX idx_recipe_machine ON recipe (machine_id);

CREATE TABLE action (
    id                     VARCHAR(36)   NOT NULL,
    recipe_id              VARCHAR(36)   NOT NULL,
    name                   VARCHAR(255)  NOT NULL,
    description            VARCHAR(2000),
    sudo                   BOOLEAN       NOT NULL,
    approval_state         VARCHAR(20)   NOT NULL,
    approved_snapshot_hash VARCHAR(64),
    approved_at            TIMESTAMP,
    -- The approving user (always via UI); a recorded id, not an FK.
    approved_by_user_id    VARCHAR(36),
    CONSTRAINT pk_action PRIMARY KEY (id),
    CONSTRAINT fk_action_recipe FOREIGN KEY (recipe_id) REFERENCES recipe (id)
);

CREATE INDEX idx_action_recipe ON action (recipe_id);

CREATE TABLE arg_token (
    id          VARCHAR(36)   NOT NULL,
    action_id   VARCHAR(36)   NOT NULL,
    position    INTEGER       NOT NULL,
    kind        VARCHAR(20)   NOT NULL,
    -- `value` is a reserved word in H2, so the column is `token_value`.
    token_value VARCHAR(1024) NOT NULL,
    CONSTRAINT pk_arg_token PRIMARY KEY (id),
    CONSTRAINT uq_arg_token_action_position UNIQUE (action_id, position),
    CONSTRAINT fk_arg_token_action FOREIGN KEY (action_id) REFERENCES action (id)
);

CREATE TABLE param_def (
    id        VARCHAR(36)   NOT NULL,
    action_id VARCHAR(36)   NOT NULL,
    name      VARCHAR(255)  NOT NULL,
    kind      VARCHAR(20)   NOT NULL,
    pattern   VARCHAR(1024),
    int_min   INTEGER,
    int_max   INTEGER,
    CONSTRAINT pk_param_def PRIMARY KEY (id),
    CONSTRAINT uq_param_def_action_name UNIQUE (action_id, name),
    CONSTRAINT fk_param_def_action FOREIGN KEY (action_id) REFERENCES action (id)
);

CREATE TABLE param_allowed_value (
    id            VARCHAR(36)   NOT NULL,
    param_def_id  VARCHAR(36)   NOT NULL,
    -- `value` is a reserved word in H2, so the column is `allowed_value`.
    allowed_value VARCHAR(1024) NOT NULL,
    CONSTRAINT pk_param_allowed_value PRIMARY KEY (id),
    CONSTRAINT fk_param_allowed_value_param_def FOREIGN KEY (param_def_id) REFERENCES param_def (id)
);

-- Audit tables (Envers validity strategy, matching machine_aud in V3): besides
-- `rev`/`revtype` each row carries `revend` — the revision at which this version
-- stopped being current (NULL while current), back-filled when the next revision
-- of the same row lands. The @ManyToOne target relations are stored un-audited, so
-- only their FK columns (machine_id / recipe_id) appear here.
CREATE TABLE recipe_aud (
    id                       VARCHAR(36) NOT NULL,
    rev                      BIGINT      NOT NULL,
    revend                   BIGINT,
    revtype                  TINYINT,
    machine_id               VARCHAR(36),
    name                     VARCHAR(255),
    description              VARCHAR(2000),
    type                     VARCHAR(20),
    source_blueprint_id      VARCHAR(36),
    source_blueprint_version INTEGER,
    created_at               TIMESTAMP,
    CONSTRAINT pk_recipe_aud PRIMARY KEY (id, rev),
    CONSTRAINT fk_recipe_aud_rev FOREIGN KEY (rev) REFERENCES revinfo (rev),
    CONSTRAINT fk_recipe_aud_revend FOREIGN KEY (revend) REFERENCES revinfo (rev)
);

CREATE TABLE action_aud (
    id                     VARCHAR(36) NOT NULL,
    rev                    BIGINT      NOT NULL,
    revend                 BIGINT,
    revtype                TINYINT,
    recipe_id              VARCHAR(36),
    name                   VARCHAR(255),
    description            VARCHAR(2000),
    sudo                   BOOLEAN,
    approval_state         VARCHAR(20),
    approved_snapshot_hash VARCHAR(64),
    approved_at            TIMESTAMP,
    approved_by_user_id    VARCHAR(36),
    CONSTRAINT pk_action_aud PRIMARY KEY (id, rev),
    CONSTRAINT fk_action_aud_rev FOREIGN KEY (rev) REFERENCES revinfo (rev),
    CONSTRAINT fk_action_aud_revend FOREIGN KEY (revend) REFERENCES revinfo (rev)
);
