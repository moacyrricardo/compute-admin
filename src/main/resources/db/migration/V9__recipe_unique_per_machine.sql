-- spec-021: discovery idempotency — one discovered recipe per (machine, type, name).
-- H2 dialect. The old, non-idempotent discovery path created a *fresh* recipe (and a
-- fresh copy of every action) on every `POST /api/machines/{id}/discover`, so a box
-- probed twice accumulated duplicate `docker`/`nginx`/... recipes. This migration
-- enforces the reconciliation identity triple `(machine_id, type, name)` at the
-- schema level via `uq_recipe_machine_type_name`.
--
-- Before the constraint can be added, any pre-existing duplicates from the old path
-- must be collapsed. Identity = `(machine_id, type, name)`; the row kept per group is
-- the earliest by `created_at` (tie-broken by the smaller `id`). Each *later* copy is
-- redundant (it was minted by re-running the same discoverer), so its action subtree
-- and any runs recorded against those redundant action copies are dropped, then the
-- duplicate recipe itself is removed. The kept (earliest) recipe retains its own
-- actions, param schema and run history untouched. Orphaned Envers `*_aud` rows are
-- left in place (they reference `revinfo`, not the live tables). This is a
-- destructive dedup, acceptable here because compute-admin is single-instance dev
-- data (see spec-021 "Implementation").

-- Gather the redundant (non-earliest) recipe ids per identity group.
CREATE LOCAL TEMPORARY TABLE dup_recipe (id VARCHAR(36) NOT NULL);

INSERT INTO dup_recipe (id)
SELECT r.id
FROM recipe r
WHERE EXISTS (
    SELECT 1 FROM recipe keep
    WHERE keep.machine_id = r.machine_id
      AND keep.type = r.type
      AND keep.name = r.name
      AND (keep.created_at < r.created_at
           OR (keep.created_at = r.created_at AND keep.id < r.id))
);

-- Drop runs recorded against the redundant recipes' action copies (FK fk_run_action).
DELETE FROM run WHERE action_id IN (
    SELECT id FROM action WHERE recipe_id IN (SELECT id FROM dup_recipe));

-- Drop the redundant recipes' action subtrees, children first (FKs).
DELETE FROM param_allowed_value WHERE param_def_id IN (
    SELECT pd.id FROM param_def pd WHERE pd.action_id IN (
        SELECT a.id FROM action a WHERE a.recipe_id IN (SELECT id FROM dup_recipe)));

DELETE FROM param_def WHERE action_id IN (
    SELECT id FROM action WHERE recipe_id IN (SELECT id FROM dup_recipe));

DELETE FROM arg_token WHERE action_id IN (
    SELECT id FROM action WHERE recipe_id IN (SELECT id FROM dup_recipe));

DELETE FROM action WHERE recipe_id IN (SELECT id FROM dup_recipe);

-- Finally the redundant recipes themselves.
DELETE FROM recipe WHERE id IN (SELECT id FROM dup_recipe);

DROP TABLE dup_recipe;

-- Enforce the identity triple going forward.
ALTER TABLE recipe
    ADD CONSTRAINT uq_recipe_machine_type_name UNIQUE (machine_id, type, name);
