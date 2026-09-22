CREATE TABLE reaction_moments (
  id TEXT PRIMARY KEY,
  message_id TEXT NOT NULL REFERENCES messages(id),
  user_id TEXT NOT NULL REFERENCES user(id),
  emoji TEXT NOT NULL,
  position_ms INTEGER NOT NULL DEFAULT 0,
  created_at INTEGER NOT NULL
);
INSERT INTO reaction_moments (id, message_id, user_id, emoji, position_ms, created_at)
SELECT message_id || ':' || user_id, message_id, user_id, emoji, 0, 0 FROM reactions;
CREATE INDEX reaction_moments_message ON reaction_moments(message_id);
-- Keep the legacy table while the previous Worker version is still serving.

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
