-- Short-lived, single-use native OAuth handoff. The browser never gets the app's verifier.
CREATE TABLE mobile_logins (
  id TEXT PRIMARY KEY,
  provider TEXT NOT NULL CHECK(provider IN ('google','apple')),
  challenge TEXT NOT NULL,
  browser TEXT,
  session_cookie TEXT,
  user_id TEXT REFERENCES user(id) ON DELETE CASCADE,
  expires_at INTEGER NOT NULL
);
CREATE INDEX mobile_login_expiry ON mobile_logins(expires_at);
