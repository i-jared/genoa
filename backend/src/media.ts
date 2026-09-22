import { Hono } from "hono";
import { z } from "zod";
import type { AppEnv, Upload } from "./types";
import {
  body,
  id,
  fail,
  membership,
  messageAccess,
  eventStatement,
} from "./access";
export const media = new Hono<AppEnv>();
const CHUNK = 8 * 1024 * 1024;
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
  const key = `media/${input.id}/original`;
  const upload = await c.env.MEDIA.createMultipartUpload(key, {
    httpMetadata: { contentType: input.mime },
  });
  try {
    await c.env.DB.batch([
      c.env.DB.prepare(
        "INSERT OR IGNORE INTO messages(id,conversation_id,author_id,kind,reply_to,state,duration_ms,created_at) VALUES(?,?,?,?,?,'uploading',?,?)",
      ).bind(
        input.id,
        input.conversation_id,
        uid,
        input.kind,
        input.reply_to ?? null,
        input.duration_ms,
        Date.now(),
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
  let obj = await c.env.MEDIA.head(u.object_key);
  if (!obj) {
    await c.env.MEDIA.resumeMultipartUpload(u.object_key, u.upload_id).complete(
      parts.results.map((p) => ({ partNumber: p.number, etag: p.etag })),
    );
    obj = await c.env.MEDIA.head(u.object_key);
  }
  if (!obj || obj.size !== u.expected_bytes)
    fail(409, "Upload size verification failed");
  // Validate magic bytes before publishing. Codec-level import normalization belongs on-device.
  const header = await c.env.MEDIA.get(u.object_key, {
    range: { offset: 0, length: 32 },
  });
  const bytes = new Uint8Array(await header!.arrayBuffer());
  const valid =
    u.mime === "image/jpeg"
      ? bytes[0] === 255 && bytes[1] === 216
      : u.mime === "image/png"
        ? bytes[0] === 137 && bytes[1] === 80
        : new TextDecoder().decode(bytes.slice(4, 8)) === "ftyp";
  if (!valid) fail(400, "Unsupported media file");
  const m = await messageAccess(c.env, u.message_id, c.get("userId"));
  await c.env.DB.batch([
    c.env.DB.prepare(
      "INSERT OR IGNORE INTO assets(id,message_id,object_key,mime,bytes) SELECT ?,?,?,?,? WHERE EXISTS(SELECT 1 FROM messages WHERE id=? AND state IN ('uploading','live','ready','failed'))",
    ).bind(u.id, u.message_id, u.object_key, u.mime, obj.size, u.message_id),
    c.env.DB.prepare(
      "UPDATE messages SET state='ready' WHERE id=? AND state IN ('uploading','live','ready','failed')",
    ).bind(u.message_id),
    c.env.DB.prepare("UPDATE uploads SET state='complete' WHERE id=?").bind(
      u.id,
    ),
    eventStatement(
      c.env,
      "message",
      {
        messageId: u.message_id,
        conversationId: m.conversation_id,
        authorId: m.author_id,
      },
      `message:${u.message_id}`,
    ),
  ]);
  const published = await c.env.DB.prepare(
    "SELECT 1 FROM assets a JOIN messages m ON m.id=a.message_id WHERE a.id=? AND m.state='ready'",
  )
    .bind(u.id)
    .first();
  if (!published) {
    await c.env.MEDIA.delete(u.object_key);
    fail(404, "Message unavailable");
  }
  return c.json({ id: u.message_id, state: "ready" });
});
media.get("/media/:id", async (c) => {
  const asset = await c.env.DB.prepare("SELECT * FROM assets WHERE id=?")
    .bind(c.req.param("id"))
    .first<{ message_id: string; object_key: string; mime: string }>();
  if (!asset) fail(404, "Media unavailable");
  await messageAccess(c.env, asset.message_id, c.get("userId"));
  const obj = await c.env.MEDIA.get(asset.object_key, {
    range: c.req.raw.headers,
  });
  if (!obj) fail(404, "Media unavailable");
  const headers = new Headers({
    "Content-Type": asset.mime,
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
});
