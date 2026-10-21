-- switchboard-hub schema v10 (M5: FORGE tickets on the board, spec F37). A bridge runs next to FORGE
-- (`forge switchboard connect`) and polls the hub with its own token; the hub relays FORGE tool calls
-- to it. FORGE stays the system of record: nothing of FORGE's is stored here.
CREATE TABLE forge_bridge (
    id TEXT PRIMARY KEY,
    hub_id TEXT NOT NULL REFERENCES hub(id),
    user_id TEXT NOT NULL REFERENCES user(id),
    name TEXT NOT NULL,
    -- Set when FORGE pairs with the one-time code; until then the code's hash waits (10 minutes, one use).
    token_hash TEXT UNIQUE,
    pair_code_hash TEXT UNIQUE,
    pair_expires_at TEXT,
    created_at TEXT NOT NULL,
    last_seen_at TEXT,
    revoked_at TEXT
);
CREATE INDEX forge_bridge_user ON forge_bridge (user_id);
