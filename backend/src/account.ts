import { Hono } from "hono";
import { z } from "zod";
import type { AppEnv } from "./types";
import { body } from "./access";
export const account = new Hono<AppEnv>();
account.get("/reminders", async (c) => {
  const rows = await c.env.DB.prepare(
    "SELECT r.*,m.conversation_id,c.title FROM reminders r JOIN messages m ON m.id=r.message_id JOIN conversations c ON c.id=m.conversation_id JOIN members cm ON cm.conversation_id=c.id AND cm.user_id=r.user_id WHERE r.user_id=? AND m.state!='deleted' ORDER BY r.due_at",
  )
    .bind(c.get("userId"))
    .all();
  return c.json(rows.results);
});
account.delete("/reminders/:id", async (c) => {
  await c.env.DB.prepare("DELETE FROM reminders WHERE id=? AND user_id=?")
    .bind(c.req.param("id"), c.get("userId"))
    .run();
  return c.json({ ok: true });
});
account.delete("/devices", async (c) => {
  await c.env.DB.prepare("DELETE FROM devices WHERE user_id=?")
    .bind(c.get("userId"))
    .run();
  return c.json({ ok: true });
});
account.get("/account/export", async (c) => {
  const uid = c.get("userId");
  const [user, messages, notes, bookmarks] = await Promise.all([
    c.env.DB.prepare("SELECT id,name,email,createdAt FROM user WHERE id=?")
      .bind(uid)
      .first(),
    c.env.DB.prepare(
      "SELECT id,conversation_id,kind,text,duration_ms,created_at,transcript,summary FROM messages WHERE author_id=? AND state!='deleted'",
    )
      .bind(uid)
      .all(),
    c.env.DB.prepare("SELECT conversation_id,text FROM notes WHERE user_id=?")
      .bind(uid)
      .all(),
    c.env.DB.prepare("SELECT message_id FROM bookmarks WHERE user_id=?")
      .bind(uid)
      .all(),
  ]);
  return c.json({
    exported_at: new Date().toISOString(),
    user,
    messages: messages.results,
    notes: notes.results,
    bookmarks: bookmarks.results,
  });
});
account.post("/account/delete", async (c) => {
  await body(c, z.object({ confirmation: z.literal("DELETE") }));
  const uid = c.get("userId");
  const owned = await c.env.DB.prepare(
    "SELECT conversation_id FROM members WHERE user_id=? AND role='owner'",
  )
    .bind(uid)
    .all<{ conversation_id: string }>();
  for (const group of owned.results) {
    const next = await c.env.DB.prepare(
      "SELECT user_id FROM members WHERE conversation_id=? AND user_id!=? ORDER BY joined_at LIMIT 1",
    )
      .bind(group.conversation_id, uid)
      .first<{ user_id: string }>();
    if (next)
      await c.env.DB.batch([
        c.env.DB.prepare(
          "UPDATE members SET role='owner' WHERE conversation_id=? AND user_id=?",
        ).bind(group.conversation_id, next.user_id),
        c.env.DB.prepare(
          "UPDATE conversations SET creator_id=? WHERE id=?",
        ).bind(next.user_id, group.conversation_id),
      ]);
    await c.env.ROOMS.get(c.env.ROOMS.idFromName(group.conversation_id)).fetch(
      "https://room/revoke",
      { method: "POST", body: JSON.stringify({ userId: uid }) },
    );
  }
  // Keep anonymous author rows for referential integrity while revoking all account access.
  const statements = [
    c.env.DB.prepare(
      "UPDATE messages SET state='deleted',text='',transcript=NULL,summary=NULL WHERE author_id=?",
    ).bind(uid),
    c.env.DB.prepare(
      "UPDATE user SET name='Deleted account',email=?,image=NULL WHERE id=?",
    ).bind(`${crypto.randomUUID()}@deleted.invalid`, uid),
    c.env.DB.prepare("DELETE FROM session WHERE userId=?").bind(uid),
    c.env.DB.prepare("DELETE FROM account WHERE userId=?").bind(uid),
    c.env.DB.prepare("DELETE FROM devices WHERE user_id=?").bind(uid),
  ];
  statements.push(
    c.env.DB.prepare(
      "INSERT OR IGNORE INTO outbox(id,kind,payload,created_at) SELECT 'delete:'||id,'delete',json_object('messageId',id),? FROM messages WHERE author_id=?",
    ).bind(Date.now(), uid),
  );
  for (const table of [
    "members",
    "profiles",
    "notes",
    "reminders",
    "bookmarks",
    "progress",
    "reactions",
    "reaction_moments",
    "blocks",
  ])
    statements.push(
      c.env.DB.prepare(`DELETE FROM ${table} WHERE user_id=?`).bind(uid),
    );
  statements.push(
    c.env.DB.prepare("DELETE FROM invites WHERE creator_id=?").bind(uid),
  );
  await c.env.DB.batch(statements);
  return c.json({ ok: true });
});
