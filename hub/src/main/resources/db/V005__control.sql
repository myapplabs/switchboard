-- switchboard-hub schema v5 (M3: control center): approvals routed to people, quick commands.

-- An agent asking before it runs something risky. Decided by the owner, admins, or (members) the requester.
CREATE TABLE approval (
    id TEXT PRIMARY KEY,
    household_id TEXT NOT NULL REFERENCES household(id),
    run_id TEXT NOT NULL REFERENCES run(id) ON DELETE CASCADE,
    session_id TEXT NOT NULL,
    agent_id TEXT NOT NULL,
    requested_by TEXT NOT NULL,
    agent_request_id TEXT,
    execution_generation INTEGER,
    command TEXT NOT NULL,
    description TEXT,
    choices_json TEXT NOT NULL,
    status TEXT NOT NULL,
    decision TEXT,
    decided_by TEXT,
    decided_at TEXT,
    expires_at TEXT,
    created_at TEXT NOT NULL
);
CREATE INDEX approval_status ON approval (household_id, status, created_at);

-- Prompt templates: personal (user_id set) or for the household (user_id null).
CREATE TABLE quick_command (
    id TEXT PRIMARY KEY,
    household_id TEXT NOT NULL REFERENCES household(id),
    user_id TEXT REFERENCES user(id) ON DELETE CASCADE,
    label TEXT NOT NULL,
    prompt TEXT NOT NULL,
    agent_id TEXT,
    position INTEGER NOT NULL DEFAULT 0,
    created_at TEXT NOT NULL
);
