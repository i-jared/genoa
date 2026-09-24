import { Hono } from "hono";
import { z } from "zod";
import type { AppEnv, Ctx, Upload } from "./types";
import {
  body,
  id,
  fail,
  membership,
  messageAccess,
  NOT_DISCARDED,
  refuseDiscarded,
} from "./access";
import { inspectMp4, playable } from "./mp4";
export const media = new Hono<AppEnv>();
const CHUNK = 8 * 1024 * 1024;
const DEFAULT_QUOTA = 25 * 1024 ** 3;
// Counts published media plus uploads still in progress, so parallel uploads can't jointly exceed the quota.
async function storageUsed(c: Ctx, uid: string, excluding: string) {
  const row = await c.env.DB.prepare(
    `SELECT COALESCE((SELECT SUM(a.bytes) FROM assets a JOIN messages m ON m.id=a.message_id WHERE m.author_id=?1 AND m.state!='deleted'),0)
      + COALESCE((SELECT SUM(expected_bytes) FROM uploads WHERE user_id=?1 AND state='open' AND id!=?2),0) used`,
  )
    .bind(uid, excluding)
    .first<{ used: number }>();
  return row?.used ?? 0;
}
media.post("/uploads", async (c) => {
  const input = await body(
    c,
    z.object({
      id,
      conversation_id: id,
      kind: z.enum(["video", "audio", "photo"]),
      mime: z.enum(["video/mp4", "audio/mp4", "image/jpeg", "image/png"]),
      bytes: z
        .number()
        .int()
        .positive()
        .max(8 * 1024 ** 3),
      duration_ms: z.number().int().nonnegative().max(86400000),
      reply_to: id.optional(),
    }),
  );
  const uid = c.get("userId");
  await membership(c.env, input.conversation_id, uid);
  if (input.reply_to) {
    const r = await messageAccess(c.env, input.reply_to, uid);
    if (r.conversation_id !== input.conversation_id)
      fail(400, "Reply must stay in its conversation");
  }
  if (
    !input.mime.startsWith(input.kind === "photo" ? "image/" : `${input.kind}/`)
  )
    fail(400, "Media type does not match message type");
  let existing = await c.env.DB.prepare("SELECT * FROM uploads WHERE id=?")
    .bind(input.id)
    .first<Upload>();
  if (existing) {
    if (existing.user_id !== uid) fail(409, "Upload ID already exists");
    await messageAccess(c.env, existing.message_id, uid);
    if (existing.expected_bytes !== input.bytes || existing.mime !== input.mime)
      fail(409, "This upload does not match the saved recording");
    if (existing.state === "expired") {
      await c.env.DB.batch([
        c.env.DB.prepare("DELETE FROM upload_parts WHERE upload_id=?").bind(
          existing.id,
        ),
        c.env.DB.prepare("DELETE FROM uploads WHERE id=?").bind(existing.id),
      ]);
      existing = null;
    }
    if (existing)
      return c.json({
        id: existing.id,
        chunk_size: CHUNK,
        state: existing.state,
      });
  }
  const priorMessage = await c.env.DB.prepare(
    "SELECT author_id,conversation_id FROM messages WHERE id=?",
  )
    .bind(input.id)
    .first();
  if (
    priorMessage &&
    (priorMessage.author_id !== uid ||
      priorMessage.conversation_id !== input.conversation_id)
  )
    fail(409, "Message ID already exists");
  if (priorMessage) await messageAccess(c.env, input.id, uid);
  await refuseDiscarded(c.env, input.id);
  const quota = Number(c.env.ACCOUNT_STORAGE_QUOTA_BYTES ?? DEFAULT_QUOTA);
  if ((await storageUsed(c, uid, input.id)) + input.bytes > quota)
    fail(
      413,
      "Your Genoa storage is full. Delete older messages you sent to make room.",
      "storage_quota",
    );
  const key = `media/${input.id}/original`;
  const upload = await c.env.MEDIA.createMultipartUpload(key, {
    httpMetadata: { contentType: input.mime },
  });
  try {
    await c.env.DB.batch([
      c.env.DB.prepare(
        `INSERT OR IGNORE INTO messages(id,conversation_id,author_id,kind,reply_to,state,duration_ms,created_at) SELECT ?,?,?,?,?,'uploading',?,? WHERE ${NOT_DISCARDED}`,
      ).bind(
        input.id,
        input.conversation_id,
        uid,
        input.kind,
        input.reply_to ?? null,
        input.duration_ms,
        Date.now(),
        input.id,
      ),
      c.env.DB.prepare(
        "UPDATE messages SET duration_ms=? WHERE id=? AND author_id=? AND state!='deleted'",
      ).bind(input.duration_ms, input.id, uid),
      c.env.DB.prepare(
        "INSERT INTO uploads(id,message_id,user_id,object_key,upload_id,mime,expected_bytes,created_at) VALUES(?,?,?,?,?,?,?,?)",
      ).bind(
        input.id,
        input.id,
        uid,
        key,
        upload.uploadId,
        input.mime,
        input.bytes,
        Date.now(),
      ),
    ]);
  } catch (e) {
    await upload.abort();
    // The uploads row references the message, so a tombstone that won the race fails the batch.
    await refuseDiscarded(c.env, input.id);
    throw e;
  }
  return c.json({ id: input.id, chunk_size: CHUNK, state: "open" }, 201);
});
async function owned(c: Parameters<typeof body>[0]) {
  const u = await c.env.DB.prepare(
    "SELECT * FROM uploads WHERE id=? AND user_id=?",
  )
    .bind(c.req.param("id"), c.get("userId"))
    .first<Upload>();
  if (!u) fail(404, "Upload unavailable");
  await messageAccess(c.env, u.message_id, c.get("userId"));
  return u;
}
media.get("/uploads/:id", async (c) => {
  const u = await owned(c);
  const parts = await c.env.DB.prepare(
    "SELECT number,etag,bytes FROM upload_parts WHERE upload_id=? ORDER BY number",
  )
    .bind(u.id)
    .all();
  return c.json({ state: u.state, parts: parts.results });
});
media.put("/uploads/:id/parts/:number", async (c) => {
  const u = await owned(c);
  if (u.state !== "open") fail(409, "Upload is already finalized");
  const number = Number(c.req.param("number"));
  if (
    !Number.isInteger(number) ||
    number < 1 ||
    number > Math.ceil(u.expected_bytes / CHUNK)
  )
    fail(400, "Invalid part number");
  const length = Number(c.req.header("content-length"));
  const expected = Math.min(CHUNK, u.expected_bytes - (number - 1) * CHUNK);
  if (length !== expected) fail(400, "Unexpected part size");
  const data = await c.req.arrayBuffer();
  if (data.byteLength !== expected) fail(400, "Unexpected part size");
  const part = await c.env.MEDIA.resumeMultipartUpload(
    u.object_key,
    u.upload_id,
  ).uploadPart(number, data);
  await c.env.DB.prepare(
    "INSERT INTO upload_parts VALUES(?,?,?,?) ON CONFLICT(upload_id,number) DO UPDATE SET etag=excluded.etag,bytes=excluded.bytes",
  )
    .bind(u.id, number, part.etag, length)
    .run();
  return c.json({ number, etag: part.etag });
});
media.post("/uploads/:id/complete", async (c) => {
  const u = await owned(c);
  if (u.state === "complete")
    return c.json({ id: u.message_id, state: "ready" });
  if (u.state === "rejected")
    fail(
      422,
      "This file couldn't be played. Try recording again or choose a different file.",
      "unsupported_media",
    );
  const parts = await c.env.DB.prepare(
    "SELECT number,etag,bytes FROM upload_parts WHERE upload_id=? ORDER BY number",
  )
    .bind(u.id)
    .all<{ number: number; etag: string; bytes: number }>();
  if (
    parts.results.length !== Math.ceil(u.expected_bytes / CHUNK) ||
    parts.results.reduce((n, p) => n + p.bytes, 0) !== u.expected_bytes
  )
    fail(409, "Some upload parts are missing");
  // Development-only hook that holds completion open so tests can interleave a deletion.
  const hold = Number(c.req.header("x-test-hold-ms") ?? 0);
  if (c.env.ENVIRONMENT === "development" && hold > 0)
    await new Promise((r) => setTimeout(r, Math.min(hold, 10000)));
  let obj = await c.env.MEDIA.head(u.object_key);
  if (!obj) {
    try {
      await c.env.MEDIA.resumeMultipartUpload(u.object_key, u.upload_id).complete(
        parts.results.map((p) => ({ partNumber: p.number, etag: p.etag })),
      );
    } catch (error) {
      // A deletion job aborts open multipart uploads; report that as the deletion it is.
      await messageAccess(c.env, u.message_id, c.get("userId"));
      throw error;
    }
    obj = await c.env.MEDIA.head(u.object_key);
  }
  // From here the object exists. Every exit that doesn't publish it must remove it, because a deletion
  // job that already ran won't come back for it.
  const reject = async (status: 422 | 409 | 404, message: string, code?: string): Promise<never> => {
    await c.env.MEDIA.delete(u.object_key);
    await c.env.DB.prepare("UPDATE uploads SET state='rejected' WHERE id=? AND state='open'")
      .bind(u.id)
      .run();
    fail(status, message, code);
  };
  if (!obj || obj.size !== u.expected_bytes)
    return reject(409, "Upload size verification failed");
  const read = async (offset: number, length: number) => {
    const part = await c.env.MEDIA.get(u.object_key, { range: { offset, length } });
    if (!part) throw new Error("MEDIA_MISSING");
    return new Uint8Array(await part.arrayBuffer());
  };
  if (!(await acceptable(u.mime, read, obj.size)))
    return reject(
      422,
      "This file couldn't be played. Try recording again or choose a different file.",
      "unsupported_media",
    );
  const uid = c.get("userId");
  const m = await c.env.DB.prepare("SELECT conversation_id,author_id FROM messages WHERE id=?")
    .bind(u.message_id)
    .first<{ conversation_id: string; author_id: string }>();
  if (!m) return reject(404, "Message unavailable");
  // Publication is conditional on the message still existing and the author still being a member,
  // evaluated atomically with the insert rather than by an earlier read.
  const publishable = `EXISTS(SELECT 1 FROM messages WHERE id=? AND state IN ('uploading','live','ready','failed'))
    AND EXISTS(SELECT 1 FROM members WHERE conversation_id=? AND user_id=?)`;
  await c.env.DB.batch([
    c.env.DB.prepare(
      `INSERT OR IGNORE INTO assets(id,message_id,object_key,mime,bytes) SELECT ?,?,?,?,? WHERE ${publishable}`,
    ).bind(u.id, u.message_id, u.object_key, u.mime, obj.size, u.message_id, m.conversation_id, uid),
    c.env.DB.prepare(
      `UPDATE messages SET state='ready' WHERE id=? AND state IN ('uploading','live','ready','failed') AND EXISTS(SELECT 1 FROM assets WHERE id=?)`,
    ).bind(u.message_id, u.id),
    c.env.DB.prepare(
      "UPDATE uploads SET state='complete' WHERE id=? AND EXISTS(SELECT 1 FROM assets WHERE id=?)",
    ).bind(u.id, u.id),
    c.env.DB.prepare(
      `INSERT OR IGNORE INTO outbox(id,kind,payload,created_at) SELECT ?,'message',?,? WHERE EXISTS(SELECT 1 FROM assets WHERE id=?)`,
    ).bind(
      `message:${u.message_id}`,
      JSON.stringify({
        messageId: u.message_id,
        conversationId: m.conversation_id,
        authorId: m.author_id,
      }),
      Date.now(),
      u.id,
    ),
  ]);
  const published = await c.env.DB.prepare(
    "SELECT 1 FROM assets a JOIN messages m ON m.id=a.message_id WHERE a.id=? AND m.state='ready'",
  )
    .bind(u.id)
    .first();
  if (!published) {
    // A deletion that commits after the batch sees the asset row and cleans it up itself.
    await c.env.DB.prepare("DELETE FROM assets WHERE id=? AND NOT EXISTS(SELECT 1 FROM messages WHERE id=? AND state='ready')")
      .bind(u.id, u.message_id)
      .run();
    return reject(404, "Message unavailable");
  }
  return c.json({ id: u.message_id, state: "ready" });
});
media.get("/media/:id", async (c) => {
  const asset = await c.env.DB.prepare("SELECT * FROM assets WHERE id=?")
    .bind(c.req.param("id"))
    .first<{ message_id: string; object_key: string; mime: string }>();
  if (!asset) fail(404, "Media unavailable");
  await messageAccess(c.env, asset.message_id, c.get("userId"));
  return serveObject(c, asset.object_key, asset.mime);
});
export async function serveObject(c: Ctx, key: string, mime: string) {
  const obj = await c.env.MEDIA.get(key, {
    range: c.req.raw.headers,
  });
  if (!obj) fail(404, "Media unavailable");
  const headers = new Headers({
    "Content-Type": mime,
    "Accept-Ranges": "bytes",
    "Cache-Control": "private, no-store",
    "X-Content-Type-Options": "nosniff",
  });
  obj.writeHttpMetadata(headers);
  headers.set("ETag", obj.httpEtag);
  if (obj.range && "offset" in obj.range && obj.range.offset !== undefined) {
    const length = obj.range.length ?? obj.size;
    headers.set(
      "Content-Range",
      `bytes ${obj.range.offset}-${obj.range.offset + length - 1}/${obj.size}`,
    );
    headers.set("Content-Length", String(length));
    return new Response(obj.body, { status: 206, headers });
  }
  headers.set("Content-Length", String(obj.size));
  return new Response(obj.body, { headers });
}


async function acceptable(
  mime: string,
  read: (offset: number, length: number) => Promise<Uint8Array>,
  size: number,
) {
  if (mime === "image/jpeg" || mime === "image/png") {
    const b = await read(0, 8);
    return mime === "image/jpeg"
      ? b[0] === 0xff && b[1] === 0xd8 && b[2] === 0xff
      : b[0] === 0x89 && b[1] === 0x50 && b[2] === 0x4e && b[3] === 0x47;
  }
  try {
    return playable(await inspectMp4(read, size), mime === "video/mp4" ? "video" : "audio");
  } catch {
    return false;
  }
}
