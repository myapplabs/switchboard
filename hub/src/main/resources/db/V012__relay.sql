-- switchboard-hub schema v12: scheduled-job results in Switchboard through Hermes's relay (EXPERIMENTAL in
-- Hermes). The agent's gateway dials the hub's /relay WebSocket with a per-agent secret (set up once with a
-- one-time code), and a job set to deliver to `relay:<delivery id>` posts its result into a conversation here.
CREATE TABLE relay_link (
    agent_id TEXT PRIMARY KEY REFERENCES agent(id) ON DELETE CASCADE,
    hub_id TEXT NOT NULL REFERENCES hub(id),
    -- Until the setup command is fetched: the one-time code's hash (10 minutes, one use).
    setup_code_hash TEXT UNIQUE,
    setup_expires_at TEXT,
    gateway_id TEXT UNIQUE,
    secret_sealed TEXT,
    created_by TEXT NOT NULL REFERENCES user(id),
    created_at TEXT NOT NULL,
    connected_at TEXT,
    last_seen_at TEXT
);
-- One per job: where its results go (a conversation of the person who turned it on).
CREATE TABLE job_delivery (
    id TEXT PRIMARY KEY,
    hub_id TEXT NOT NULL REFERENCES hub(id),
    agent_id TEXT NOT NULL REFERENCES agent(id) ON DELETE CASCADE,
    job_id TEXT NOT NULL,
    job_name TEXT NOT NULL,
    user_id TEXT NOT NULL REFERENCES user(id) ON DELETE CASCADE,
    session_id TEXT,
    created_at TEXT NOT NULL,
    last_delivered_at TEXT,
    UNIQUE (agent_id, job_id)
);
