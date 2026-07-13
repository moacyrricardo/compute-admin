-- spec-015: custom-script content-pinning. Closes the approve-then-run TOCTOU hole
-- for CUSTOM actions by binding a SHA-256 of the wrapped script's bytes (probed over
-- SSH) at approval and re-verifying it before each run. H2 dialect.
--
-- A sibling nullable column on `action` (NOT folded into the pure, offline
-- ActionSnapshot content hash): null = "not pinned", so already-approved actions keep
-- working until re-approved (no back-fill probe of every box on migrate). Audited by
-- inheritance (Action is @Audited), so `action_aud` gets the matching column.
ALTER TABLE action ADD COLUMN approved_script_hash VARCHAR(64);
ALTER TABLE action_aud ADD COLUMN approved_script_hash VARCHAR(64);
