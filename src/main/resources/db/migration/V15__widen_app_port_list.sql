-- spec-056: widen the discovery app_port_list from VARCHAR(4000) to CLOB.
-- 056 unions three new sweeps (listening + non-listening + fingerprinted common
-- services) into the (app-name, port) pre-fill and enlarges each item with 055's
-- three path fields plus a sourceNote provenance string. On a busy host the JSON
-- array outgrows 4000 chars, so DiscoveryService.persist(...) would throw on the
-- VARCHAR bound. Widen to CLOB (same @Lob mapping the Run stdout/stderr columns use
-- in V5) — a capacity change only, no format change, still the same JSON shape
-- RunService binds per fan-out item. app_port_list stays @NotAudited, so there is no
-- recipe_aud column to widen.

ALTER TABLE recipe ALTER COLUMN app_port_list SET DATA TYPE CLOB;
