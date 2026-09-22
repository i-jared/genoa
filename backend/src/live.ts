import { Hono } from "hono";
import { z } from "zod";
import type { AppEnv } from "./types";
import {
  body,
  id,
  fail,
  membership,
  messageAccess,
  eventStatement,
} from "./access";
export const live = new Hono<AppEnv>();
live.post("/conversations/:id/live", async (c) => {
  const input = await body(
    c,
    z.object({
      id,
      rotation: z
        .union([z.literal(0), z.literal(90), z.literal(180), z.literal(270)])
        .default(0),
      reply_to: id.optional(),
    }),
  );
  const uid = c.get("userId"),
    cid = c.req.param("id");
  await membership(c.env, cid, uid);
  if (input.reply_to) {
    const target = await messageAccess(c.env, input.reply_to, uid);
    if (target.conversation_id !== cid)
      fail(400, "Reply must stay in its conversation");
  }
  const prior = await c.env.DB.prepare(
    "SELECT author_id,conversation_id FROM messages WHERE id=?",
  )
    .bind(input.id)
    .first();
  if (prior && (prior.author_id !== uid || prior.conversation_id !== cid))
    fail(409, "Message ID already exists");
  await c.env.DB.prepare(
    "INSERT OR IGNORE INTO messages(id,conversation_id,author_id,kind,state,created_at,live_activity,live_rotation,reply_to) VALUES(?,?,?,'video','live',?,?,?,?)",
  )
    .bind(
      input.id,
      cid,
      uid,
      Date.now(),
      Date.now(),
      input.rotation,
      input.reply_to ?? null,
    )
    .run();
  await c.env.ROOMS.get(c.env.ROOMS.idFromName(cid)).fetch(
    "https://room/event",
    { method: "POST", body: JSON.stringify({ type: "changed" }) },
  );
  return c.json({ id: input.id });
});
live.put("/live/:id/segments/:number", async (c) => {
  const mid = c.req.param("id"),
    m = await messageAccess(c.env, mid, c.get("userId"));
  if (m.author_id !== c.get("userId"))
    fail(403, "Only the sender can publish segments");
  if (m.state !== "live") fail(409, "This recording has ended");
  const number = Number(c.req.param("number")),
    duration = Number(c.req.query("duration"));
  if (
    !Number.isInteger(number) ||
    number < 0 ||
    number > 10000 ||
    !Number.isFinite(duration) ||
    duration <= 0 ||
    duration > 30
  )
    fail(400, "Invalid segment");
  const length = Number(c.req.header("content-length"));
  if (!length || length > 12 * 1024 * 1024) fail(413, "Segment too large");
  const prior = await c.env.DB.prepare(
    "SELECT object_key FROM live_segments WHERE message_id=? AND number=?",
  )
    .bind(mid, number)
    .first();
  if (prior) return c.json({ ok: true });
  const key = `media/${mid}/live/${number}.ts`;
  await c.env.MEDIA.put(key, c.req.raw.body, {
    httpMetadata: { contentType: "video/mp2t" },
  });
  await c.env.DB.prepare(
    "INSERT OR IGNORE INTO live_segments SELECT ?,?,?,? WHERE EXISTS(SELECT 1 FROM messages WHERE id=? AND state='live')",
  )
    .bind(mid, number, key, duration, mid)
    .run();
  await c.env.DB.prepare(
    "UPDATE messages SET live_activity=? WHERE id=? AND state='live'",
  )
    .bind(Date.now(), mid)
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
live.get("/live/:id/playlist.m3u8", async (c) => {
  const mid = c.req.param("id"),
    m = await messageAccess(c.env, mid, c.get("userId"));
  const rows = await c.env.DB.prepare(
    "SELECT number,duration FROM live_segments WHERE message_id=? ORDER BY number",
  )
    .bind(mid)
    .all<{ number: number; duration: number }>();
  let expected = 0;
  const contiguous = rows.results.filter((s) =>
    s.number === expected ? !!++expected : false,
  );
  const target = Math.max(2, ...contiguous.map((s) => Math.ceil(s.duration)));
  const lines = [
    "#EXTM3U",
    "#EXT-X-VERSION:3",
    `#EXT-X-TARGETDURATION:${target}`,
    "#EXT-X-MEDIA-SEQUENCE:0",
    "#EXT-X-PLAYLIST-TYPE:EVENT",
    ...contiguous.flatMap((s) => [
      `#EXTINF:${s.duration.toFixed(3)},`,
      `segments/${s.number}.ts`,
    ]),
  ];
  if (m.state !== "live") lines.push("#EXT-X-ENDLIST");
  return new Response(lines.join("\n") + "\n", {
    headers: {
      "Content-Type": "application/vnd.apple.mpegurl",
      "Cache-Control": "private, no-store",
    },
  });
});
live.get("/live/:id/segments/:file", async (c) => {
  const mid = c.req.param("id");
  await messageAccess(c.env, mid, c.get("userId"));
  const file = c.req.param("file");
  if (!/^\d+\.ts$/.test(file)) fail(400, "Invalid segment");
  const asset = await c.env.DB.prepare(
    "SELECT object_key FROM live_segments WHERE message_id=? AND number=?",
  )
    .bind(mid, Number(file.slice(0, -3)))
    .first<{ object_key: string }>();
  if (!asset) fail(404, "Segment unavailable");
  const obj = await c.env.MEDIA.get(asset.object_key);
  if (!obj) fail(404, "Segment unavailable");
  return new Response(obj.body, {
    headers: {
      "Content-Type": "video/mp2t",
      "Cache-Control": "private, no-store",
    },
  });
});
live.post("/live/:id/end", async (c) => {
  const m = await messageAccess(c.env, c.req.param("id"), c.get("userId"));
  if (m.author_id !== c.get("userId"))
    fail(403, "Only the sender can end the recording");
  await c.env.DB.batch([
    c.env.DB.prepare(
      "UPDATE messages SET state='ready',duration_ms=COALESCE((SELECT SUM(duration)*1000 FROM live_segments WHERE message_id=?),0) WHERE id=? AND state='live'",
    ).bind(m.id, m.id),
    eventStatement(
      c.env,
      "message",
      {
        messageId: m.id,
        conversationId: m.conversation_id,
        authorId: m.author_id,
      },
      `message:${m.id}`,
    ),
  ]);
  return c.json({ ok: true });
});
