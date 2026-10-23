-- switchboard-hub schema v11: scheduled-job run history. Agents keep only a job's last run (Hermes cron:
-- last_run_at, last_status, last_error), so the hub checks every minute and records each new run, to show
-- a history and notify on failures. The job itself stays the agent's.
CREATE TABLE job_watch (
    agent_id TEXT NOT NULL REFERENCES agent(id) ON DELETE CASCADE,
    job_id TEXT NOT NULL,
    last_run_at TEXT,
    seen_at TEXT NOT NULL,
    PRIMARY KEY (agent_id, job_id)
);
CREATE TABLE job_run (
    id TEXT PRIMARY KEY,
    agent_id TEXT NOT NULL REFERENCES agent(id) ON DELETE CASCADE,
    job_id TEXT NOT NULL,
    ran_at TEXT NOT NULL,
    result TEXT,
    error TEXT,
    recorded_at TEXT NOT NULL,
    UNIQUE (agent_id, job_id, ran_at)
);
CREATE INDEX job_run_job ON job_run (agent_id, job_id, ran_at);
