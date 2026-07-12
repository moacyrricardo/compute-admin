-- spec-022: fan-out parent/child run rows.
-- A monitor poll over a repeatable (app-name, port) list persists one parent `run`
-- for the poll plus one child `run` per item, so each child stays a faithful
-- spec-005 record (one item's argv, one exit code) while the poll keeps a single
-- handle. `parent_run_id` is a nullable self-reference (null for the parent row and
-- for the pre-022 scalar path, which is a fan-out of size 1). `app_label` tags a
-- child with its item's appName so the dashboard (spec-024) can route per-app output.
--
-- `run` stays a NOT @Audited append-only log, so no _aud handling is needed here.

ALTER TABLE run ADD COLUMN parent_run_id VARCHAR(36);
ALTER TABLE run ADD COLUMN app_label VARCHAR(64);

ALTER TABLE run ADD CONSTRAINT fk_run_parent
    FOREIGN KEY (parent_run_id) REFERENCES run (id);

CREATE INDEX idx_run_parent ON run (parent_run_id);
