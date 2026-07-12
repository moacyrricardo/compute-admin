-- spec-025: the discovery-pre-filled (app-name, port) list for an app-monitor recipe.
-- The app-monitor families (springboot / fastapi / generic) each fan out their probe
-- actions over a repeatable (app-name, port) list (spec-022). That list is a RUNTIME
-- value the discoverer pre-fills and the UI edits — NOT part of any action's content
-- hash — so changing which apps are probed needs no re-approval. It lives on the
-- recipe (one list shared by all the recipe's probe actions), stored as the same JSON
-- array shape RunService already binds per item: [{"appName","port","runtime"}].
--
-- Nullable: only app-monitor recipes carry it; host-vitals (spec-023) and every
-- non-monitor recipe leave it NULL. `recipe` is @Audited but this column is a
-- @NotAudited runtime value, so no matching recipe_aud column is needed (the same
-- treatment the action's structural children get in spec-004).

ALTER TABLE recipe ADD COLUMN app_port_list VARCHAR(4000);
