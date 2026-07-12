-- spec-014: email+password auth replaces Google sign-in (revises spec-011 auth).
-- Greenfield clean wipe — no production users exist. Rows are deleted in FK order
-- (personal_token references app_user via fk_personal_token_owner), then app_user
-- drops its Google subject and gains a bcrypt password_hash. H2 dialect.
DELETE FROM personal_token;   -- FK -> app_user; must precede app_user rows
DELETE FROM pairing_request;
DELETE FROM app_user;

ALTER TABLE app_user DROP CONSTRAINT uq_app_user_google_sub;
ALTER TABLE app_user DROP COLUMN google_sub;
ALTER TABLE app_user ADD COLUMN password_hash VARCHAR(255) NOT NULL;
