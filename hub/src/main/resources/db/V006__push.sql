-- switchboard-hub schema v6 (M4: push through the AppLabs relay). Notices carry no content; the app
-- fetches details from the hub. relay_grant: the relay's signature of push_token (see push-relay/).
ALTER TABLE device ADD COLUMN push_token TEXT;
ALTER TABLE device ADD COLUMN relay_grant TEXT;
ALTER TABLE device ADD COLUMN notify_prefs_json TEXT NOT NULL DEFAULT '{}';
