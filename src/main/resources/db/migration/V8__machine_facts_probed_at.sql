-- spec-018: mark when the read-only OS/cloud facts probe ran for a machine, so
-- auto-tagging runs once per machine (add-only) and never re-adds a user-removed
-- auto-tag. Operational marker, @NotAudited on the entity — no machine_aud column.
ALTER TABLE machine ADD COLUMN facts_probed_at TIMESTAMP;
