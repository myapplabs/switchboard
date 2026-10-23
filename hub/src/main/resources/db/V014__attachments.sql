-- switchboard-hub schema v14: files and photos sent with a message. The bytes live in the data folder's
-- files/ directory, named by id. Uploaded first (message_id NULL), then attached when the message is sent;
-- unsent uploads are cleaned up after a day. The agent gets a download link: a random token, stored hashed.
CREATE TABLE attachment (
    id TEXT PRIMARY KEY,
    hub_id TEXT NOT NULL REFERENCES hub(id),
    session_id TEXT NOT NULL REFERENCES chat_session(id) ON DELETE CASCADE,
    message_id TEXT,
    owner_user_id TEXT NOT NULL,
    name TEXT NOT NULL,
    content_type TEXT NOT NULL,
    size INTEGER NOT NULL,
    agent_token_hash TEXT UNIQUE,
    agent_token_expires_at TEXT,
    created_at TEXT NOT NULL
);
CREATE INDEX attachment_session ON attachment (session_id);
CREATE INDEX attachment_message ON attachment (message_id);
