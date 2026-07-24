-- spec-049: widen the ArgToken script column so a longer approved probe script fits.
-- The app-folder/footprint probe (AppMonitorDiscoverer) is a single fixed `sh -c` script
-- token that does the whole /proc + filesystem walk on the target — an order of magnitude
-- longer than the cpu/process probes, which comfortably fit the original VARCHAR(1024).
-- token_value stores one argv element verbatim (S4: the script is a source-controlled
-- constant, never caller-assembled), so it must hold the full literal. This is a pure
-- column-width bump — no new table, no new column, no new RecipeType, no model change —
-- applied symmetrically to the recipe and blueprint token stores (both @NotAudited, so no
-- `_aud` tables). H2 dialect.
ALTER TABLE arg_token ALTER COLUMN token_value SET DATA TYPE VARCHAR(16384);
ALTER TABLE blueprint_arg_token ALTER COLUMN token_value SET DATA TYPE VARCHAR(16384);
