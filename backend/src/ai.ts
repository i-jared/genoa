import { Hono } from "hono";
import type { AppEnv } from "./types";
import { fail, messageAccess } from "./access";
export const ai = new Hono<AppEnv>();
ai.put("/messages/:id/audio/:number", async (c) => {
  const mid = c.req.param("id"),
    m = await messageAccess(c.env, mid, c.get("userId"));
  if (m.author_id !== c.get("userId"))
    fail(403, "Only the author can supply audio");
  const number = Number(c.req.param("number")),
    offset = Number(c.req.query("offset_ms"));
  if (
    !Number.isInteger(number) ||
    number < 0 ||
    number > 10000 ||
    !Number.isSafeInteger(offset) ||
    offset < 0
  )
    fail(400, "Invalid audio chunk");
  const length = Number(c.req.header("content-length"));
  if (!length || length > 5 * 1024 * 1024)
    fail(413, "Audio chunk is too large");
  const key = `media/${mid}/audio/${number}`;
  await c.env.MEDIA.put(key, c.req.raw.body, {
    httpMetadata: { contentType: "audio/mp4" },
  });
  await c.env.DB.prepare(
    "INSERT INTO audio_chunks(message_id,number,object_key,offset_ms) SELECT ?,?,?,? WHERE EXISTS(SELECT 1 FROM messages WHERE id=? AND state!='deleted') ON CONFLICT(message_id,number) DO NOTHING",
  )
    .bind(mid, number, key, offset, mid)
    .run();
  const stillAvailable = await c.env.DB.prepare(
    "SELECT 1 FROM messages WHERE id=? AND state!='deleted'",
  )
    .bind(mid)
    .first();
  if (!stillAvailable) {
    await c.env.MEDIA.delete(key);
    fail(404, "Message unavailable");
  }
  return c.json({ ok: true });
});
ai.get("/messages/:id/captions", async (c) => {
  await messageAccess(c.env, c.req.param("id"), c.get("userId"));
  const rows = await c.env.DB.prepare(
    "SELECT start_ms,end_ms,text FROM captions WHERE message_id=? ORDER BY number",
  )
    .bind(c.req.param("id"))
    .all();
  return c.json(rows.results);
});
