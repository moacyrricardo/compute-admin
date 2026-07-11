-- spec-005: the execution engine's append-only run log.
-- H2 dialect. `run` records one execution of an approved action against a machine:
-- who ran it (caller_user_id + via), the supplied params snapshot, the exact argv
-- executed, the approved snapshot hash in force at run time, the lifecycle status,
-- the captured exit code, and streamed stdout/stderr.
--
-- `run` is NOT @Audited — it is already immutable execution history, so it has no
-- `_aud` shadow table (ARCH.md: Run is an append-only log, not versioned config).
-- Ownership derives through action -> recipe -> machine -> owner; there is no owner
-- column here.

CREATE TABLE run (
    id                     VARCHAR(36)   NOT NULL,
    action_id              VARCHAR(36)   NOT NULL,
    machine_id             VARCHAR(36)   NOT NULL,
    caller_user_id         VARCHAR(36)   NOT NULL,
    via                    VARCHAR(20)   NOT NULL,
    params_json            VARCHAR(4000),
    resolved_argv_json     VARCHAR(4000) NOT NULL,
    approved_snapshot_hash VARCHAR(64)   NOT NULL,
    status                 VARCHAR(20)   NOT NULL,
    exit_code              INTEGER,
    stdout                 CLOB,
    stderr                 CLOB,
    created_at             TIMESTAMP     NOT NULL,
    started_at             TIMESTAMP,
    finished_at            TIMESTAMP,
    CONSTRAINT pk_run PRIMARY KEY (id),
    CONSTRAINT fk_run_action FOREIGN KEY (action_id) REFERENCES action (id),
    CONSTRAINT fk_run_machine FOREIGN KEY (machine_id) REFERENCES machine (id)
);

CREATE INDEX idx_run_action ON run (action_id);
CREATE INDEX idx_run_machine ON run (machine_id);
