import { HTTPException } from "hono/http-exception";
import { z } from "zod";
import type { Ctx, Env, Message } from "./types";
export const id = z.string().uuid();
export type Status = 400 | 401 | 403 | 404 | 409 | 410 | 413 | 422 | 429 | 502 | 503;
// `code` lets clients distinguish terminal failures (quota, unsupported media, suspension) from retryable ones.
export class ApiError extends HTTPException {
  constructor(
    status: Status,
    message: string,
    readonly code?: string,
  ) {
    super(status, { message });
  }
}
export function fail(status: Status, message: string, code?: string): never {
  throw new ApiError(status, message, code);
}
const JSON_LIMIT = 64 * 1024;
export async function body<T>(
  c: Ctx,
  schema: z.ZodType<T>,
  limit = JSON_LIMIT,
): Promise<T> {
  if (Number(c.req.header("content-length") ?? 0) > limit)
    fail(413, "Request is too large");
  const text = await c.req.text().catch(() => "");
  if (text.length > limit) fail(413, "Request is too large");
  let parsed: unknown = null;
  try {
    parsed = JSON.parse(text);
  } catch {}
  const value = schema.safeParse(parsed);
  if (!value.success)
    fail(400, value.error.issues[0]?.message ?? "Invalid request");
  return value.data;
}
export async function knownContact(env: Env, uid: string, other: string) {
  return env.DB.prepare(
    `SELECT 1 FROM members mine
     JOIN members theirs ON theirs.conversation_id=mine.conversation_id AND theirs.user_id=?
     WHERE mine.user_id=?
       AND NOT EXISTS(SELECT 1 FROM blocks WHERE (user_id=? AND blocked_id=?) OR (user_id=? AND blocked_id=?))`,
  )
    .bind(other, uid, uid, other, other, uid)
    .first();
}
export async function membership(env: Env, cid: string, uid: string) {
  const member = await env.DB.prepare(
    "SELECT * FROM members WHERE conversation_id=? AND user_id=?",
  )
    .bind(cid, uid)
    .first<{ role: string; joined_at: number }>();
  if (!member) fail(404, "Conversation unavailable");
  return member;
}
export async function messageAccess(env: Env, mid: string, uid: string) {
  const message = await env.DB.prepare(
    `SELECT m.* FROM messages m JOIN members cm ON cm.conversation_id=m.conversation_id AND cm.user_id=? WHERE m.id=? AND m.state!='deleted' AND m.created_at>=cm.joined_at AND NOT EXISTS (SELECT 1 FROM blocks WHERE user_id=? AND blocked_id=m.author_id)`,
  )
    .bind(uid, mid, uid)
    .first<Message>();
  if (!message) fail(404, "Message unavailable");
  return message;
}
export function eventStatement(
  env: Env,
  kind: string,
  payload: Record<string, unknown>,
  eventId = crypto.randomUUID(),
) {
  return env.DB.prepare(
    "INSERT OR IGNORE INTO outbox(id,kind,payload,created_at) VALUES(?,?,?,?)",
  ).bind(eventId, kind, JSON.stringify(payload), Date.now());
}
export async function rateLimit(env: Env, key: string, max: number) {
  const window = Math.floor(Date.now() / 60000);
  const row = await env.DB.prepare(
    `INSERT INTO rate_limits(key,count,window) VALUES(?,1,?) ON CONFLICT(key) DO UPDATE SET count=CASE WHEN window=excluded.window THEN count+1 ELSE 1 END, window=excluded.window RETURNING count`,
  )
    .bind(key, window)
    .first<{ count: number }>();
  if ((row?.count ?? 0) > max) fail(429, "Please wait a minute and try again");
}

export async function suspension(env: Env, uid: string) {
  return env.DB.prepare("SELECT reason FROM suspensions WHERE user_id=?")
    .bind(uid)
    .first<{ reason: string }>();
}
// Live sockets are hints, but a removed or suspended person must stop receiving them immediately.
export async function revokeLive(env: Env, uid: string, conversationIds: string[]) {
  await Promise.all(
    conversationIds.map((cid) =>
      env.ROOMS.get(env.ROOMS.idFromName(cid))
        .fetch("https://room/revoke", {
          method: "POST",
          body: JSON.stringify({ userId: uid }),
        })
        .catch(() => console.error(JSON.stringify({ category: "LIVE_REVOKE_FAILED", cid }))),
    ),
  );
}
export async function memberConversations(env: Env, uid: string) {
  const rows = await env.DB.prepare(
    "SELECT conversation_id FROM members WHERE user_id=?",
  )
    .bind(uid)
    .all<{ conversation_id: string }>();
  return rows.results.map((r) => r.conversation_id);
}
export function deleteMessageStatements(env: Env, mid: string) {
  return [
    env.DB.prepare(
      "UPDATE messages SET state='deleted',text='',transcript=NULL,summary=NULL WHERE id=?",
    ).bind(mid),
    eventStatement(env, "delete", { messageId: mid }, `delete:${mid}`),
  ];
}

// Appended to message INSERT ... SELECT statements; binds the message ID.
export const NOT_DISCARDED = "NOT EXISTS(SELECT 1 FROM message_tombstones WHERE id=?)";
export async function refuseDiscarded(env: Env, mid: string) {
  if (await env.DB.prepare("SELECT 1 FROM message_tombstones WHERE id=?").bind(mid).first())
    fail(410, "This message was discarded", "message_discarded");
}
