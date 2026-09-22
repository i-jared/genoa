-- Older local installs already applied the original 0005, which dropped this
-- table. Retain it during the production Worker cutover and mirror legacy writes.
CREATE TABLE IF NOT EXISTS reactions (
  message_id TEXT NOT NULL REFERENCES messages(id),
  user_id TEXT NOT NULL REFERENCES user(id),
  emoji TEXT NOT NULL,
  PRIMARY KEY(message_id,user_id)
);
CREATE TRIGGER IF NOT EXISTS legacy_reaction_insert AFTER INSERT ON reactions BEGIN
  INSERT INTO reaction_moments(id,message_id,user_id,emoji,position_ms,created_at)
  VALUES(NEW.message_id || ':' || NEW.user_id,NEW.message_id,NEW.user_id,NEW.emoji,0,0)
  ON CONFLICT(id) DO UPDATE SET emoji=excluded.emoji;
END;
CREATE TRIGGER IF NOT EXISTS legacy_reaction_update AFTER UPDATE ON reactions BEGIN
  INSERT INTO reaction_moments(id,message_id,user_id,emoji,position_ms,created_at)
  VALUES(NEW.message_id || ':' || NEW.user_id,NEW.message_id,NEW.user_id,NEW.emoji,0,0)
  ON CONFLICT(id) DO UPDATE SET emoji=excluded.emoji;
END;
CREATE TRIGGER IF NOT EXISTS legacy_reaction_delete AFTER DELETE ON reactions BEGIN
  DELETE FROM reaction_moments WHERE id=OLD.message_id || ':' || OLD.user_id;
END;
