-- switchboard-hub schema v7: neutral names (the hub serves families and companies alike).
-- household → hub, household_id → hub_id, shared_household → shared_everyone. Data is kept;
-- SQLite updates the foreign keys that point at the renamed table. Hub ids keep their hh_ prefix (opaque).
ALTER TABLE household RENAME TO hub;
ALTER TABLE user RENAME COLUMN household_id TO hub_id;
ALTER TABLE membership RENAME COLUMN household_id TO hub_id;
ALTER TABLE token RENAME COLUMN household_id TO hub_id;
ALTER TABLE agent RENAME COLUMN household_id TO hub_id;
ALTER TABLE agent_profile RENAME COLUMN household_id TO hub_id;
ALTER TABLE activity RENAME COLUMN household_id TO hub_id;
ALTER TABLE credential RENAME COLUMN household_id TO hub_id;
ALTER TABLE device RENAME COLUMN household_id TO hub_id;
ALTER TABLE setup_code RENAME COLUMN household_id TO hub_id;
ALTER TABLE chat_session RENAME COLUMN household_id TO hub_id;
ALTER TABLE message RENAME COLUMN household_id TO hub_id;
ALTER TABLE run RENAME COLUMN household_id TO hub_id;
ALTER TABLE agent_grant RENAME COLUMN household_id TO hub_id;
ALTER TABLE invite RENAME COLUMN household_id TO hub_id;
ALTER TABLE approval RENAME COLUMN household_id TO hub_id;
ALTER TABLE quick_command RENAME COLUMN household_id TO hub_id;
ALTER TABLE chat_session RENAME COLUMN shared_household TO shared_everyone;
DROP INDEX activity_household_at;
CREATE INDEX activity_hub_at ON activity (hub_id, at);
