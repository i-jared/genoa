-- Moderation, operations and diagnostics. Admin access is granted only by inserting rows here.
CREATE TABLE admins (
  user_id TEXT PRIMARY KEY REFERENCES user(id) ON DELETE CASCADE,
  created_at INTEGER NOT NULL
);
CREATE TABLE suspensions (
  user_id TEXT PRIMARY KEY REFERENCES user(id) ON DELETE CASCADE,
  reason TEXT NOT NULL,
  admin_id TEXT NOT NULL,
  created_at INTEGER NOT NULL
);
CREATE TABLE admin_audit (
  id TEXT PRIMARY KEY,
  admin_id TEXT NOT NULL,
  action TEXT NOT NULL,
  target_type TEXT NOT NULL,
  target_id TEXT NOT NULL,
  note TEXT,
  created_at INTEGER NOT NULL
);
CREATE INDEX admin_audit_time ON admin_audit(created_at);
ALTER TABLE reports ADD COLUMN status TEXT NOT NULL DEFAULT 'open';
ALTER TABLE reports ADD COLUMN resolution TEXT;
ALTER TABLE reports ADD COLUMN note TEXT;
ALTER TABLE reports ADD COLUMN resolved_at INTEGER;
ALTER TABLE reports ADD COLUMN resolved_by TEXT;
CREATE INDEX report_queue ON reports(status,created_at);
CREATE INDEX report_message ON reports(message_id);
-- Jobs that exhausted queue retries, recorded by the dead-letter consumer for triage and redrive.
CREATE TABLE failed_jobs (
  id TEXT PRIMARY KEY,
  kind TEXT NOT NULL,
  payload TEXT NOT NULL,
  attempts INTEGER NOT NULL DEFAULT 0,
  failed_at INTEGER NOT NULL
);
-- Client crash/hang reports. The payload lives in R2 under diagnostics/<id>.json.
CREATE TABLE diagnostics (
  id TEXT PRIMARY KEY,
  user_id TEXT NOT NULL,
  platform TEXT NOT NULL,
  app_version TEXT NOT NULL,
  build TEXT NOT NULL,
  os_version TEXT NOT NULL,
  kind TEXT NOT NULL,
  bytes INTEGER NOT NULL,
  created_at INTEGER NOT NULL
);
CREATE INDEX diagnostics_time ON diagnostics(created_at);
CREATE TABLE maintenance (key TEXT PRIMARY KEY, value TEXT NOT NULL, updated_at INTEGER NOT NULL);
CREATE INDEX message_author ON messages(author_id,state);
CREATE INDEX reaction_moment_author ON reaction_moments(message_id,user_id);
-- A delete of an ID the server hasn't seen yet (a discard racing its own upload setup) is remembered,
-- so a request still in flight can't create that message afterwards. Clients may then treat 404 as final.
CREATE TABLE message_tombstones (id TEXT PRIMARY KEY, user_id TEXT NOT NULL, created_at INTEGER NOT NULL);
