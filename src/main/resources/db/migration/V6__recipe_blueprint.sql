-- spec-010: recipe blueprints — author once, instantiate per-machine.
-- H2 dialect. Adds `recipe_blueprint` and its `blueprint_action` (structured argv
-- via `blueprint_arg_token`, typed param schema via `blueprint_param_def` +
-- `blueprint_param_allowed_value`), mirroring the recipe/action command shape from
-- V4 but with NO machine, NO approval state and NO run path — a blueprint is never
-- runnable. `revinfo` already exists from V3 (spec-003).
--
-- Only `recipe_blueprint` and `blueprint_action` are @Audited, so only they get
-- hand-written `_aud` companions (Envers validity strategy: rev/revend/revtype).
-- The structural children are @NotAudited (their content is captured indirectly by
-- an instantiated action's approved snapshot hash), so no _aud tables for them.
--
-- The instantiation provenance columns on `recipe` (`source_blueprint_id` /
-- `source_blueprint_version`) already exist from V4 (spec-004).

CREATE TABLE recipe_blueprint (
    id          VARCHAR(36)   NOT NULL,
    owner_id    VARCHAR(36)   NOT NULL,
    name        VARCHAR(255)  NOT NULL,
    description VARCHAR(2000),
    type        VARCHAR(20)   NOT NULL,
    version     INTEGER       NOT NULL,
    created_at  TIMESTAMP     NOT NULL,
    updated_at  TIMESTAMP     NOT NULL,
    CONSTRAINT pk_recipe_blueprint PRIMARY KEY (id),
    CONSTRAINT fk_recipe_blueprint_owner FOREIGN KEY (owner_id) REFERENCES app_user (id)
);

CREATE INDEX idx_recipe_blueprint_owner ON recipe_blueprint (owner_id);

CREATE TABLE blueprint_action (
    id           VARCHAR(36)   NOT NULL,
    blueprint_id VARCHAR(36)   NOT NULL,
    name         VARCHAR(255)  NOT NULL,
    description  VARCHAR(2000),
    sudo         BOOLEAN       NOT NULL,
    CONSTRAINT pk_blueprint_action PRIMARY KEY (id),
    CONSTRAINT fk_blueprint_action_blueprint FOREIGN KEY (blueprint_id) REFERENCES recipe_blueprint (id)
);

CREATE INDEX idx_blueprint_action_blueprint ON blueprint_action (blueprint_id);

CREATE TABLE blueprint_arg_token (
    id                  VARCHAR(36)   NOT NULL,
    blueprint_action_id VARCHAR(36)   NOT NULL,
    position            INTEGER       NOT NULL,
    kind                VARCHAR(20)   NOT NULL,
    -- `value` is a reserved word in H2, so the column is `token_value` (as in V4).
    token_value         VARCHAR(1024) NOT NULL,
    CONSTRAINT pk_blueprint_arg_token PRIMARY KEY (id),
    CONSTRAINT uq_blueprint_arg_token_action_position UNIQUE (blueprint_action_id, position),
    CONSTRAINT fk_blueprint_arg_token_action FOREIGN KEY (blueprint_action_id) REFERENCES blueprint_action (id)
);

CREATE TABLE blueprint_param_def (
    id                  VARCHAR(36)   NOT NULL,
    blueprint_action_id VARCHAR(36)   NOT NULL,
    name                VARCHAR(255)  NOT NULL,
    kind                VARCHAR(20)   NOT NULL,
    pattern             VARCHAR(1024),
    int_min             INTEGER,
    int_max             INTEGER,
    CONSTRAINT pk_blueprint_param_def PRIMARY KEY (id),
    CONSTRAINT uq_blueprint_param_def_action_name UNIQUE (blueprint_action_id, name),
    CONSTRAINT fk_blueprint_param_def_action FOREIGN KEY (blueprint_action_id) REFERENCES blueprint_action (id)
);

CREATE TABLE blueprint_param_allowed_value (
    id                    VARCHAR(36)   NOT NULL,
    blueprint_param_def_id VARCHAR(36)  NOT NULL,
    -- `value` is a reserved word in H2, so the column is `allowed_value` (as in V4).
    allowed_value         VARCHAR(1024) NOT NULL,
    CONSTRAINT pk_blueprint_param_allowed_value PRIMARY KEY (id),
    CONSTRAINT fk_blueprint_param_allowed_value_def FOREIGN KEY (blueprint_param_def_id) REFERENCES blueprint_param_def (id)
);

-- Audit tables (Envers validity strategy, matching machine_aud/recipe_aud): besides
-- `rev`/`revtype` each row carries `revend` — the revision at which this version
-- stopped being current (NULL while current), back-filled when the next revision of
-- the same row lands. The @ManyToOne target relations are stored un-audited, so only
-- their FK columns (owner_id / blueprint_id) appear here.
CREATE TABLE recipe_blueprint_aud (
    id          VARCHAR(36) NOT NULL,
    rev         BIGINT      NOT NULL,
    revend      BIGINT,
    revtype     TINYINT,
    owner_id    VARCHAR(36),
    name        VARCHAR(255),
    description VARCHAR(2000),
    type        VARCHAR(20),
    version     INTEGER,
    created_at  TIMESTAMP,
    updated_at  TIMESTAMP,
    CONSTRAINT pk_recipe_blueprint_aud PRIMARY KEY (id, rev),
    CONSTRAINT fk_recipe_blueprint_aud_rev FOREIGN KEY (rev) REFERENCES revinfo (rev),
    CONSTRAINT fk_recipe_blueprint_aud_revend FOREIGN KEY (revend) REFERENCES revinfo (rev)
);

CREATE TABLE blueprint_action_aud (
    id           VARCHAR(36) NOT NULL,
    rev          BIGINT      NOT NULL,
    revend       BIGINT,
    revtype      TINYINT,
    blueprint_id VARCHAR(36),
    name         VARCHAR(255),
    description  VARCHAR(2000),
    sudo         BOOLEAN,
    CONSTRAINT pk_blueprint_action_aud PRIMARY KEY (id, rev),
    CONSTRAINT fk_blueprint_action_aud_rev FOREIGN KEY (rev) REFERENCES revinfo (rev),
    CONSTRAINT fk_blueprint_action_aud_revend FOREIGN KEY (revend) REFERENCES revinfo (rev)
);
