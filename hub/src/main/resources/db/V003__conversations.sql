-- switchboard-hub schema v3 (M1: conversations and runs). Contract 0.2.0, hybrid: the hub stores every
-- conversation; agents with their own sessions continue `agent_session_id` instead of being sent history.

CREATE TABLE chat_session (
    id TEXT PRIMARY KEY,
    household_id TEXT NOT NULL REFERENCES household(id),
    agent_id TEXT NOT NULL REFERENCES agent(id) ON DELETE CASCADE,
    owner_user_id TEXT NOT NULL REFERENCES user(id) ON DELETE CASCADE,
    title TEXT NOT NULL,
    title_is_default INTEGER NOT NULL DEFAULT 1,
    model_id TEXT,
    agent_session_id TEXT NOT NULL,
    last_message_preview TEXT,
    input_tokens INTEGER NOT NULL DEFAULT 0,
    output_tokens INTEGER NOT NULL DEFAULT 0,
    estimated_cost_usd REAL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);
CREATE INDEX chat_session_owner ON chat_session (owner_user_id, updated_at);

-- The transcript the app shows. kind: message | steer | clarification_answer (user messages).
CREATE TABLE message (
    id TEXT PRIMARY KEY,
    household_id TEXT NOT NULL REFERENCES household(id),
    session_id TEXT NOT NULL REFERENCES chat_session(id) ON DELETE CASCADE,
    role TEXT NOT NULL CHECK (role IN ('user', 'assistant')),
    kind TEXT NOT NULL DEFAULT 'message',
    author_user_id TEXT,
    text TEXT NOT NULL DEFAULT '',
    run_id TEXT,
    status TEXT,
    model_label TEXT,
    created_at TEXT NOT NULL
);
CREATE INDEX message_session ON message (session_id, created_at);

-- One agent turn. agent_last_seq: the agent's last event seen (dedupe when re-reading a stream).
CREATE TABLE run (
    id TEXT PRIMARY KEY,
    household_id TEXT NOT NULL REFERENCES household(id),
    session_id TEXT NOT NULL REFERENCES chat_session(id) ON DELETE CASCADE,
    agent_id TEXT NOT NULL,
    user_id TEXT NOT NULL,
    agent_run_id TEXT,
    agent_last_seq INTEGER NOT NULL DEFAULT -1,
    status TEXT NOT NULL,
    message_id TEXT NOT NULL,
    model_label TEXT,
    output TEXT,
    error TEXT,
    usage_json TEXT,
    stopped_by TEXT,
    created_at TEXT NOT NULL,
    finished_at TEXT
);
CREATE INDEX run_session ON run (session_id, created_at);
CREATE INDEX run_status ON run (status);

-- The run's stream as the app saw it, replayed with Last-Event-ID.
CREATE TABLE run_event (
    run_id TEXT NOT NULL REFERENCES run(id) ON DELETE CASCADE,
    seq INTEGER NOT NULL,
    type TEXT NOT NULL,
    payload_json TEXT NOT NULL,
    PRIMARY KEY (run_id, seq)
);

CREATE TABLE tool_call (
    id TEXT NOT NULL,
    run_id TEXT NOT NULL REFERENCES run(id) ON DELETE CASCADE,
    message_id TEXT NOT NULL,
    tool TEXT NOT NULL,
    preview TEXT,
    state TEXT NOT NULL,
    duration_s REAL,
    output_excerpt TEXT,
    position INTEGER NOT NULL,
    PRIMARY KEY (run_id, id)
);

-- Questions the agent asked mid-run (Hermes 0.22 clarify).
CREATE TABLE clarification (
    id TEXT PRIMARY KEY,
    run_id TEXT NOT NULL REFERENCES run(id) ON DELETE CASCADE,
    message_id TEXT NOT NULL,
    agent_request_id TEXT NOT NULL,
    execution_generation INTEGER,
    question TEXT NOT NULL,
    choices_json TEXT NOT NULL DEFAULT '[]',
    multi_select INTEGER NOT NULL DEFAULT 0,
    status TEXT NOT NULL,
    answer_json TEXT,
    answered_by TEXT,
    expires_at TEXT,
    created_at TEXT NOT NULL
);

-- Agent-side sessions still to delete (the agent was offline when the conversation was deleted).
CREATE TABLE agent_cleanup (
    agent_id TEXT NOT NULL,
    agent_session_id TEXT NOT NULL,
    user_scope TEXT NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    next_at TEXT NOT NULL,
    PRIMARY KEY (agent_id, agent_session_id)
);
