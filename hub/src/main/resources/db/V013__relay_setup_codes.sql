-- switchboard-hub schema v13: relay setup codes kept apart from the link. Each code stays valid until it is
-- used or expires (10 minutes), so making a second code no longer cancels the first, and a working
-- connection is replaced only when a new code is actually used.
CREATE TABLE relay_setup_code (
    code_hash TEXT PRIMARY KEY,
    agent_id TEXT NOT NULL REFERENCES agent(id) ON DELETE CASCADE,
    hub_id TEXT NOT NULL REFERENCES hub(id),
    created_by TEXT NOT NULL REFERENCES user(id),
    expires_at TEXT NOT NULL,
    created_at TEXT NOT NULL
);
-- Links still waiting for their first code to be used move over (their codes stay usable).
INSERT INTO relay_setup_code (code_hash, agent_id, hub_id, created_by, expires_at, created_at)
    SELECT setup_code_hash, agent_id, hub_id, created_by, setup_expires_at, created_at FROM relay_link
    WHERE setup_code_hash IS NOT NULL AND setup_expires_at IS NOT NULL;
DELETE FROM relay_link WHERE secret_sealed IS NULL;
