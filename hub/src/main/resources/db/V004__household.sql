-- switchboard-hub schema v4 (M2: household): invites, agent grants, shared conversations, activity of users.

-- Invite codes: single use, 24 h, role preset; only the code's hash is stored.
CREATE TABLE invite (
    id TEXT PRIMARY KEY,
    household_id TEXT NOT NULL REFERENCES household(id),
    code_hash TEXT NOT NULL UNIQUE,
    role TEXT NOT NULL CHECK (role IN ('admin', 'member', 'restricted')),
    note TEXT,
    created_by TEXT NOT NULL REFERENCES user(id),
    created_at TEXT NOT NULL,
    expires_at TEXT NOT NULL,
    used_by TEXT,
    used_at TEXT,
    revoked_at TEXT
);

-- Who may use an agent, on which profile (tool level) and models. Deny by default.
-- subject_kind 'role' (subject = role name) or 'user' (subject = user id; wins over the role's grant).
-- models_json: [{"model_id": "...", "daily_limit": n|null}]; empty = only the agent's default model.
CREATE TABLE agent_grant (
    agent_id TEXT NOT NULL REFERENCES agent(id) ON DELETE CASCADE,
    household_id TEXT NOT NULL REFERENCES household(id),
    subject_kind TEXT NOT NULL CHECK (subject_kind IN ('role', 'user')),
    subject TEXT NOT NULL,
    profile_id TEXT,
    can_chat INTEGER NOT NULL DEFAULT 0,
    can_manage_jobs INTEGER NOT NULL DEFAULT 0,
    daily_run_limit INTEGER,
    models_json TEXT NOT NULL DEFAULT '[]',
    PRIMARY KEY (agent_id, subject_kind, subject)
);

-- Shared conversations: with chosen members, or the whole household.
ALTER TABLE chat_session ADD COLUMN shared_household INTEGER NOT NULL DEFAULT 0;
CREATE TABLE session_participant (
    session_id TEXT NOT NULL REFERENCES chat_session(id) ON DELETE CASCADE,
    user_id TEXT NOT NULL REFERENCES user(id) ON DELETE CASCADE,
    PRIMARY KEY (session_id, user_id)
);

ALTER TABLE user ADD COLUMN last_active_at TEXT;
CREATE INDEX run_user_agent ON run (user_id, agent_id, created_at);
ALTER TABLE run ADD COLUMN model_id TEXT;
ALTER TABLE run ADD COLUMN profile_id TEXT;
