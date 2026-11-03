-- switchboard-hub schema v15: FORGE tickets on the board are removed (owner, 2026-11-01: Switchboard doesn't include
-- FORGE). The bridges table from v10 goes with them; v10 itself stays, as applied migrations are never edited.
DROP INDEX IF EXISTS forge_bridge_user;
DROP TABLE IF EXISTS forge_bridge;
