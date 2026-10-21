-- switchboard-hub schema v1 (M0: households, users, tokens, agents, profiles, activity).
-- Every table carries household_id so the same code can serve organisations in the hosted tier.

CREATE TABLE household (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    plan TEXT NOT NULL DEFAULT 'self_hosted',
    created_at TEXT NOT NULL
);

CREATE TABLE user (
    id TEXT PRIMARY KEY,
    household_id TEXT NOT NULL REFERENCES household(id),
    display_name TEXT NOT NULL,
    created_at TEXT NOT NULL,
    disabled_at TEXT
);

CREATE TABLE membership (
    user_id TEXT NOT NULL REFERENCES user(id) ON DELETE CASCADE,
    household_id TEXT NOT NULL REFERENCES household(id),
    role TEXT NOT NULL CHECK (role IN ('owner', 'admin', 'member', 'restricted')),
    joined_at TEXT NOT NULL,
    PRIMARY KEY (user_id, household_id)
);

-- Bearer tokens: only a SHA-256 hash is stored. kind: access | refresh | admin (issued on the host by the CLI).
CREATE TABLE token (
    hash TEXT PRIMARY KEY,
    household_id TEXT NOT NULL REFERENCES household(id),
    user_id TEXT NOT NULL REFERENCES user(id) ON DELETE CASCADE,
    kind TEXT NOT NULL,
    device_name TEXT,
    created_at TEXT NOT NULL,
    expires_at TEXT NOT NULL,
    revoked_at TEXT
);

CREATE TABLE agent (
    id TEXT PRIMARY KEY,
    household_id TEXT NOT NULL REFERENCES household(id),
    type TEXT NOT NULL,
    name TEXT NOT NULL,
    base_url TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'unknown',
    activity TEXT,
    active_runs INTEGER NOT NULL DEFAULT 0,
    capabilities_json TEXT NOT NULL DEFAULT '{}',
    last_seen_at TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

-- One per tool level / model on the agent (e.g. 'safe', 'full'); the API key is encrypted (SecretBox).
CREATE TABLE agent_profile (
    id TEXT PRIMARY KEY,
    household_id TEXT NOT NULL REFERENCES household(id),
    agent_id TEXT NOT NULL REFERENCES agent(id) ON DELETE CASCADE,
    name TEXT NOT NULL,
    port INTEGER,
    api_key_encrypted TEXT NOT NULL,
    key_last4 TEXT NOT NULL,
    toolset_summary TEXT,
    position INTEGER NOT NULL DEFAULT 0
);

-- Append-only; metadata only, never message content.
CREATE TABLE activity (
    id TEXT PRIMARY KEY,
    household_id TEXT NOT NULL REFERENCES household(id),
    at TEXT NOT NULL,
    user_id TEXT,
    agent_id TEXT,
    action TEXT NOT NULL,
    detail_json TEXT NOT NULL DEFAULT '{}'
);

CREATE INDEX activity_household_at ON activity (household_id, at);
