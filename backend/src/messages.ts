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
export const messages = new Hono<AppEnv>();
const messageSelect = `SELECT m.*,u.name author_name, a.id asset_id,a.mime,
 COALESCE(p.position_ms,0) position_ms,COALESCE(p.watched,0) watched,
 EXISTS(SELECT 1 FROM bookmarks b WHERE b.message_id=m.id AND b.user_id=?) bookmarked,
 (SELECT json_group_array(json_object('id',r.id,'user_id',r.user_id,'user_name',ru.name,'emoji',r.emoji,'position_ms',r.position_ms,'created_at',r.created_at)) FROM reaction_moments r JOIN user ru ON ru.id=r.user_id WHERE r.message_id=m.id) reactions
 FROM messages m JOIN user u ON u.id=m.author_id
 LEFT JOIN assets a ON a.message_id=m.id AND a.purpose='original'
 LEFT JOIN progress p ON p.message_id=m.id AND p.user_id=?
 WHERE m.conversation_id=? AND m.created_at>=? AND (m.state IN ('ready','live') OR (m.author_id=? AND m.state='uploading'))
 AND NOT EXISTS(SELECT 1 FROM blocks WHERE user_id=? AND blocked_id=m.author_id)`;
const decodeMessages = (rows: Record<string, unknown>[]) =>
  rows.map((r) => ({ ...r, reactions: JSON.parse(String(r.reactions)) }));

// A bounded, contiguous window around the first unseen incoming message. Cursor
// paging and refresh use the same membership, join-time, and block visibility.
messages.get("/conversations/:id/watch", async (c) => {
  const cid = c.req.param("id"),
    uid = c.get("userId");
  const member = await membership(c.env, cid, uid);
  const keys = ["before", "after", "around", "message"] as const;
  const supplied = keys.filter((key) => c.req.query(key) !== undefined);
  if (supplied.length > 1) fail(400, "Use one history cursor");
  const mode = supplied[0];
  const cursor = mode && mode !== "message" ? Number(c.req.query(mode)) : undefined;
  if (cursor !== undefined && (!Number.isSafeInteger(cursor) || cursor < 0))
    fail(400, "Invalid cursor");
  const bindings = [uid, uid, cid, member.joined_at, uid, uid];
  const visible = `WITH visible AS (${messageSelect})`;
  let anchor = cursor;
  let focusId: string | null = null;
  if (mode === "message") {
    const target = id.safeParse(c.req.query("message"));
    if (!target.success) fail(400, "Invalid message");
    const row = await c.env.DB.prepare(
      `${visible} SELECT id,seq FROM visible WHERE id=?`,
    ).bind(...bindings, target.data).first<{ id: string; seq: number }>();
    if (!row) fail(404, "Message unavailable");
    anchor = row.seq;
    focusId = row.id;
  }
  if (!mode) {
    const first = await c.env.DB.prepare(
      `${visible} SELECT id,seq FROM visible WHERE author_id!=? AND watched=0 AND state IN ('ready','live') ORDER BY seq LIMIT 1`,
    )
      .bind(...bindings, uid)
      .first<{ id: string; seq: number }>();
    const focus =
      first ??
      (await c.env.DB.prepare(
        `${visible} SELECT id,seq FROM visible ORDER BY seq DESC LIMIT 1`,
      )
        .bind(...bindings)
        .first<{ id: string; seq: number }>());
    anchor = focus?.seq;
    focusId = focus?.id ?? null;
  }
  let rows: Record<string, unknown>[] = [];
  if (anchor !== undefined) {
    if (mode === "before" || mode === "after") {
      const forward = mode === "after";
      const page = await c.env.DB.prepare(
        `${visible} SELECT * FROM visible WHERE seq ${forward ? ">" : "<"} ? ORDER BY seq ${forward ? "ASC" : "DESC"} LIMIT 50`,
      )
        .bind(...bindings, anchor)
        .all();
      rows = forward ? page.results : page.results.reverse();
    } else {
      const page = await c.env.DB.prepare(
        `${visible}
        SELECT * FROM (SELECT * FROM visible WHERE seq<? ORDER BY seq DESC LIMIT 25)
        UNION ALL SELECT * FROM (SELECT * FROM visible WHERE seq>=? ORDER BY seq ASC LIMIT 25)
        ORDER BY seq`,
      )
        .bind(...bindings, anchor, anchor)
        .all();
      rows = page.results;
    }
  }
  const bounds = await c.env.DB.prepare(
    `${visible} SELECT MIN(seq) first,MAX(seq) last FROM visible`,
  )
    .bind(...bindings)
    .first<{ first: number | null; last: number | null }>();
  const low = rows.length ? Number(rows[0]!.seq) : (anchor ?? 0);
  const high = rows.length ? Number(rows[rows.length - 1]!.seq) : (anchor ?? 0);
  return c.json({
    messages: decodeMessages(rows),
    focus_id: focusId,
    start_seq: mode === "around" ? Math.min(low, anchor!) : low,
    end_seq: mode === "around" ? Math.max(high, anchor!) : high,
    has_older:
      bounds?.first != null &&
      bounds.first < low &&
      (mode !== "before" || rows.length > 0),
    has_newer:
      bounds?.last != null &&
      bounds.last > high &&
      (mode !== "after" || rows.length > 0),
  });
});
messages.get("/conversations/:id/messages", async (c) => {
  const cid = c.req.param("id"),
    uid = c.get("userId");
  const member = await membership(c.env, cid, uid);
  const bookmarked = c.req.query("bookmarked") === "true";
  const before = Number(c.req.query("before") ?? Number.MAX_SAFE_INTEGER);
  if (!Number.isSafeInteger(before) || before < 0) fail(400, "Invalid cursor");
  const rows = await c.env.DB.prepare(
    `${messageSelect} AND m.seq<?
 AND (?=0 OR EXISTS(SELECT 1 FROM bookmarks b WHERE b.message_id=m.id AND b.user_id=?))
 ORDER BY m.seq DESC LIMIT 50`,
  )
    .bind(
      uid,
      uid,
      cid,
      member.joined_at,
      uid,
      uid,
      before,
      Number(bookmarked),
      uid,
    )
    .all();
  return c.json(decodeMessages(rows.results).reverse());
});
messages.post("/conversations/:id/messages", async (c) => {
  const input = await body(
    c,
    z.object({
      id,
      text: z.string().trim().min(1).max(10000),
      reply_to: id.optional(),
      urgent: z.boolean().default(false),
    }),
  );
  const cid = c.req.param("id"),
    uid = c.get("userId");
  await membership(c.env, cid, uid);
  if (input.reply_to) {
    const target = await messageAccess(c.env, input.reply_to, uid);
    if (target.conversation_id !== cid)
      fail(400, "Reply must stay in its conversation");
  }
  const existing = await c.env.DB.prepare(
    "SELECT author_id,conversation_id FROM messages WHERE id=?",
  )
    .bind(input.id)
    .first();
  if (
    existing &&
    (existing.author_id !== uid || existing.conversation_id !== cid)
  )
    fail(409, "Message ID already exists");
  await c.env.DB.batch([
    c.env.DB.prepare(
      "INSERT OR IGNORE INTO messages(id,conversation_id,author_id,kind,text,reply_to,urgent,state,created_at) VALUES(?,?,?,'text',?,?,?,'ready',?)",
    ).bind(
      input.id,
      cid,
      uid,
      input.text,
      input.reply_to ?? null,
      Number(input.urgent),
      Date.now(),
    ),
    eventStatement(
      c.env,
      "message",
      { messageId: input.id, conversationId: cid, authorId: uid },
      `message:${input.id}`,
    ),
  ]);
  return c.json({ id: input.id }, 201);
});
messages.put("/messages/:id/reaction", async (c) => {
  const input = await body(
    c,
    z.object({
      id: id.optional(),
      emoji: z.string().min(1).max(32),
      position_ms: z.number().int().nonnegative().max(86400000).default(0),
    }),
  );
  const mid = c.req.param("id"),
    uid = c.get("userId");
  const m = await messageAccess(c.env, mid, uid);
  const reactionId = input.id ?? crypto.randomUUID();
  await c.env.DB.prepare(
    "INSERT OR IGNORE INTO reaction_moments(id,message_id,user_id,emoji,position_ms,created_at) VALUES(?,?,?,?,?,?)",
  )
    .bind(reactionId, mid, uid, input.emoji, input.position_ms, Date.now())
    .run();
  const saved = await c.env.DB.prepare(
    "SELECT message_id,user_id,emoji,position_ms FROM reaction_moments WHERE id=?",
  )
    .bind(reactionId)
    .first();
  if (
    saved?.message_id !== mid ||
    saved.user_id !== uid ||
    saved.emoji !== input.emoji ||
    saved.position_ms !== input.position_ms
  )
    fail(409, "Reaction ID already exists");
  c.executionCtx.waitUntil(
    c.env.ROOMS.get(c.env.ROOMS.idFromName(m.conversation_id))
      .fetch("https://room/event", {
        method: "POST",
        body: JSON.stringify({ type: "changed" }),
      })
      .catch(() => console.error("REACTION_SYNC_HINT_FAILED")),
  );
  return c.json({ id: reactionId, ok: true });
});
messages.put("/messages/:id/progress", async (c) => {
  const input = await body(
    c,
    z.object({
      position_ms: z.number().int().nonnegative().max(86400000),
      watched: z.boolean(),
    }),
  );
  const mid = c.req.param("id"),
    uid = c.get("userId");
  await messageAccess(c.env, mid, uid);
  await c.env.DB.prepare(
    "INSERT INTO progress VALUES(?,?,?,?) ON CONFLICT(message_id,user_id) DO UPDATE SET position_ms=excluded.position_ms,watched=MAX(watched,excluded.watched)",
  )
    .bind(mid, uid, input.position_ms, Number(input.watched))
    .run();
  return c.json({ ok: true });
});
messages.put("/messages/:id/bookmark", async (c) => {
  const { enabled } = await body(c, z.object({ enabled: z.boolean() }));
  const mid = c.req.param("id"),
    uid = c.get("userId");
  await messageAccess(c.env, mid, uid);
  await c.env.DB.prepare(
    enabled
      ? "INSERT OR IGNORE INTO bookmarks VALUES(?,?)"
      : "DELETE FROM bookmarks WHERE message_id=? AND user_id=?",
  )
    .bind(mid, uid)
    .run();
  return c.json({ ok: true });
});
messages.post("/messages/:id/reminder", async (c) => {
  const input = await body(
    c,
    z.object({
      id,
      due_at: z
        .number()
        .int()
        .min(Date.now() + 1000)
        .max(Date.now() + 365 * 86400000),
    }),
  );
  const mid = c.req.param("id"),
    uid = c.get("userId");
  await messageAccess(c.env, mid, uid);
  await c.env.DB.prepare("INSERT OR IGNORE INTO reminders VALUES(?,?,?,?,0)")
    .bind(input.id, mid, uid, input.due_at)
    .run();
  return c.json({ ok: true });
});
messages.delete("/messages/:id", async (c) => {
  const mid = c.req.param("id"),
    uid = c.get("userId");
  const row = await c.env.DB.prepare(
    "SELECT author_id,state,conversation_id FROM messages WHERE id=?",
  )
    .bind(mid)
    .first();
  if (!row) fail(404, "Message unavailable");
  if (row.author_id !== uid) fail(403, "You can only delete your own messages");
  await c.env.DB.batch([
    c.env.DB.prepare(
      "UPDATE messages SET state='deleted',text='',transcript=NULL,summary=NULL WHERE id=?",
    ).bind(mid),
    eventStatement(c.env, "delete", { messageId: mid }, `delete:${mid}`),
  ]);
  return c.json({ ok: true });
});
messages.post("/messages/:id/forward", async (c) => {
  const input = await body(c, z.object({ id, conversation_id: id }));
  const uid = c.get("userId"),
    m = await messageAccess(c.env, c.req.param("id"), uid);
  await membership(c.env, input.conversation_id, uid);
  if (m.state !== "ready") fail(409, "Wait until the message is ready");
  const prior = await c.env.DB.prepare(
    "SELECT author_id,conversation_id FROM messages WHERE id=?",
  )
    .bind(input.id)
    .first();
  if (prior) {
    if (
      prior.author_id !== uid ||
      prior.conversation_id !== input.conversation_id
    )
      fail(409, "Message ID already exists");
    return c.json({ id: input.id });
  }
  const asset = await c.env.DB.prepare(
    "SELECT * FROM assets WHERE message_id=? AND purpose='original'",
  )
    .bind(m.id)
    .first<{ object_key: string; mime: string; bytes: number }>();
  const aid = crypto.randomUUID(),
    key = `media/${input.id}/${aid}`;
  if (asset) {
    const obj = await c.env.MEDIA.get(asset.object_key);
    if (!obj) fail(404, "Media unavailable");
    await c.env.MEDIA.put(key, obj.body, {
      httpMetadata: { contentType: asset.mime },
    });
  }
  const statements = [
    c.env.DB.prepare(
      "INSERT INTO messages(id,conversation_id,author_id,kind,text,state,duration_ms,created_at) SELECT ?,?,?,kind,text,'ready',duration_ms,? FROM messages WHERE id=? AND state='ready'",
    ).bind(input.id, input.conversation_id, uid, Date.now(), m.id),
  ];
  if (asset)
    statements.push(
      c.env.DB.prepare(
        "INSERT INTO assets(id,message_id,object_key,mime,bytes) VALUES(?,?,?,?,?)",
      ).bind(aid, input.id, key, asset.mime, asset.bytes),
    );
  statements.push(
    eventStatement(
      c.env,
      "message",
      {
        messageId: input.id,
        conversationId: input.conversation_id,
        authorId: uid,
      },
      `message:${input.id}`,
    ),
  );
  try {
    await c.env.DB.batch(statements);
  } catch (e) {
    if (asset) await c.env.MEDIA.delete(key);
    throw e;
  }
  return c.json({ id: input.id });
});
messages.post("/messages/:id/ai", async (c) => {
  const mid = c.req.param("id"),
    uid = c.get("userId"),
    m = await messageAccess(c.env, mid, uid);
  const consent = await c.env.DB.prepare(
    "SELECT ai_consent FROM profiles WHERE user_id=?",
  )
    .bind(m.author_id)
    .first<{ ai_consent: number }>();
  if (!consent?.ai_consent)
    fail(403, "The sender has not enabled AI processing");
  if (!c.env.AI) fail(503, "Transcription is not configured on this server");
  if (m.state !== "ready") fail(409, "Wait until the message is ready");
  await c.env.DB.batch([
    c.env.DB.prepare(
      "UPDATE messages SET ai_status='pending' WHERE id=? AND state='ready'",
    ).bind(mid),
    eventStatement(c.env, "ai", { messageId: mid }),
  ]);
  return c.json({ ok: true });
});
