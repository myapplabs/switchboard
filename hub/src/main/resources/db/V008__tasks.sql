-- switchboard-hub schema v8 (M5: the task board; spec "Task board: agents working together").
-- What each agent is good at, set by the owner or an admin (separate from tool profiles).
CREATE TABLE agent_specialty (
    agent_id TEXT PRIMARY KEY REFERENCES agent(id) ON DELETE CASCADE,
    hub_id TEXT NOT NULL REFERENCES hub(id),
    about TEXT NOT NULL DEFAULT '',
    tags_json TEXT NOT NULL DEFAULT '[]',
    cost_level TEXT,
    updated_at TEXT NOT NULL
);

-- A card. column: backlog, ready, working, needs_you, review, done. reason: why it needs someone.
CREATE TABLE task (
    id TEXT PRIMARY KEY,
    hub_id TEXT NOT NULL REFERENCES hub(id),
    title TEXT NOT NULL,
    instructions TEXT NOT NULL DEFAULT '',
    created_by TEXT NOT NULL REFERENCES user(id),
    board_column TEXT NOT NULL DEFAULT 'backlog',
    reason TEXT,
    reason_detail TEXT,
    outcome TEXT,
    source TEXT NOT NULL DEFAULT 'switchboard',
    source_ref TEXT,
    due TEXT,
    review_each_step INTEGER NOT NULL DEFAULT 0,
    runs_used INTEGER NOT NULL DEFAULT 0,
    send_backs INTEGER NOT NULL DEFAULT 0,
    max_runs INTEGER NOT NULL DEFAULT 12,
    max_send_backs INTEGER NOT NULL DEFAULT 3,
    max_minutes INTEGER NOT NULL DEFAULT 120,
    first_started_at TEXT,
    position REAL NOT NULL DEFAULT 0,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);
CREATE INDEX task_board ON task (hub_id, board_column, position);

-- A card's steps, in order. Exactly one of agent_id / tag. status: pending, running, done, failed.
CREATE TABLE task_step (
    id TEXT PRIMARY KEY,
    task_id TEXT NOT NULL REFERENCES task(id) ON DELETE CASCADE,
    position INTEGER NOT NULL,
    agent_id TEXT REFERENCES agent(id) ON DELETE SET NULL,
    tag TEXT,
    model_id TEXT,
    status TEXT NOT NULL DEFAULT 'pending',
    session_id TEXT REFERENCES chat_session(id) ON DELETE SET NULL,
    run_id TEXT,
    ran_on_agent_id TEXT,
    result TEXT,
    note TEXT,
    started_at TEXT,
    finished_at TEXT
);
CREATE INDEX task_step_run ON task_step (run_id);
CREATE UNIQUE INDEX task_step_order ON task_step (task_id, position);
