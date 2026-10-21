-- switchboard-hub schema v9 (M5: the coordinator, spec F36). One agent per hub may coordinate: a card
-- given to it gets a plan step (it proposes sub-cards) and a combine step (it merges their results).
-- Sub-cards exist only after a person approves the plan; they count toward the parent's limits.
ALTER TABLE agent_specialty ADD COLUMN coordinator INTEGER NOT NULL DEFAULT 0;
ALTER TABLE task ADD COLUMN parent_id TEXT REFERENCES task(id) ON DELETE CASCADE;
ALTER TABLE task ADD COLUMN coordinated INTEGER NOT NULL DEFAULT 0;
ALTER TABLE task ADD COLUMN plan_json TEXT;
ALTER TABLE task_step ADD COLUMN kind TEXT NOT NULL DEFAULT 'work';
CREATE INDEX task_parent ON task (parent_id);
