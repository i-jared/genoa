import { HTTPException } from "hono/http-exception";
import { z } from "zod";
import type { Ctx, Env, Message } from "./types";
export const id = z.string().uuid();
export function fail(
  status: 400 | 401 | 403 | 404 | 409 | 410 | 413 | 502 | 429 | 503,
  message: string,
): never {
  throw new HTTPException(status, { message });
}
export async function body<T>(c: Ctx, schema: z.ZodType<T>): Promise<T> {
  const value = schema.safeParse(await c.req.json().catch(() => null));
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
