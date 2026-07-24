-- spec-049: a run's resolved argv can embed the full app-folder/footprint probe — a
-- single fixed `sh -c` script token (~6 KB, and larger once spec-050 lifecycle scans
-- land) — which overflows RUN.RESOLVED_ARGV_JSON VARCHAR(4000) at insert time
-- (SQLState 22001). V15 widened the ArgToken *template* store, but the per-run resolved
-- argv is a separate column and was missed. Store it unbounded as CLOB, exactly like the
-- already-@Lob stdout/stderr columns — a maxed VARCHAR(16384) arg_token JSON-escapes past
-- any fixed width, so a hard limit is the wrong tool here. Pure column-type widen: no new
-- table, no new column, no model change (RUN is @NotAudited — no `_aud` table). H2 dialect.
ALTER TABLE run ALTER COLUMN resolved_argv_json SET DATA TYPE CLOB;
