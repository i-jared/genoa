ALTER TABLE messages ADD COLUMN live_activity INTEGER NOT NULL DEFAULT 0;
CREATE INDEX upload_cleanup ON uploads(state,created_at);
