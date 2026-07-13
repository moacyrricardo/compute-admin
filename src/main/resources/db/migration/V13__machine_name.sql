-- spec-028: machine name & MCP identity hardening (resolves ARCH S9).
-- Adds a user-provided, per-owner-unique `name` to `machine` (the MCP-facing
-- identifier) and its `machine_aud` audit column. H2 dialect. Existing rows are
-- backfilled with `name = host` before the NOT NULL + unique constraint land
-- (a one-time seed of the owner's own data into an editable field — see spec-028
-- Known Gaps). `machine_aud.name` stays nullable, matching the existing _aud
-- pattern (V3__machine.sql).

ALTER TABLE machine ADD COLUMN name VARCHAR(255);
UPDATE machine SET name = host WHERE name IS NULL;
ALTER TABLE machine ALTER COLUMN name SET NOT NULL;
ALTER TABLE machine ADD CONSTRAINT uq_machine_owner_name UNIQUE (owner_id, name);

ALTER TABLE machine_aud ADD COLUMN name VARCHAR(255);
