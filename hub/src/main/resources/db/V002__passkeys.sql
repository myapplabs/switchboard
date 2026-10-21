-- switchboard-hub schema v2 (M0 step 3: passkeys, devices, setup codes).

-- WebAuthn user handle (random, not the user id). Set when the user's first passkey is registered.
ALTER TABLE user ADD COLUMN webauthn_handle TEXT;
CREATE UNIQUE INDEX user_webauthn_handle ON user (webauthn_handle);

-- Passkeys. id = credential id (base64url). Public key in COSE form; no private material ever reaches the hub.
CREATE TABLE credential (
    id TEXT PRIMARY KEY,
    household_id TEXT NOT NULL REFERENCES household(id),
    user_id TEXT NOT NULL REFERENCES user(id) ON DELETE CASCADE,
    public_key_cose TEXT NOT NULL,
    sign_count INTEGER NOT NULL DEFAULT 0,
    device_name TEXT,
    created_at TEXT NOT NULL,
    last_used_at TEXT
);

-- A signed-in device (one per sign-in). Revoking it ends all of its tokens.
CREATE TABLE device (
    id TEXT PRIMARY KEY,
    household_id TEXT NOT NULL REFERENCES household(id),
    user_id TEXT NOT NULL REFERENCES user(id) ON DELETE CASCADE,
    credential_id TEXT REFERENCES credential(id) ON DELETE SET NULL,
    name TEXT,
    platform TEXT,
    created_at TEXT NOT NULL,
    last_used_at TEXT,
    revoked_at TEXT
);

ALTER TABLE token ADD COLUMN device_id TEXT REFERENCES device(id) ON DELETE CASCADE;

-- Pairing codes for the owner's devices, printed on the hub machine. Hash only; single use.
CREATE TABLE setup_code (
    hash TEXT PRIMARY KEY,
    household_id TEXT NOT NULL REFERENCES household(id),
    created_at TEXT NOT NULL,
    expires_at TEXT NOT NULL,
    used_at TEXT,
    failed_attempts INTEGER NOT NULL DEFAULT 0
);
