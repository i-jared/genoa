import { Hono } from "hono";
import { z } from "zod";
import type { AppEnv } from "./types";
import { body, id, fail, membership, knownContact } from "./access";
export const conversations = new Hono<AppEnv>();
conversations.get("/", async (c) => {
  const result = await c.env.DB.prepare(
    `SELECT c.id,c.creator_id,c.created_at,
    COALESCE(NULLIF(c.title,''),(SELECT group_concat(name, ', ') FROM (SELECT u.name FROM members peers JOIN user u ON u.id=peers.user_id WHERE peers.conversation_id=c.id AND peers.user_id!=cm.user_id ORDER BY peers.joined_at,u.id LIMIT 3)), 'New conversation') title,
    cm.muted, cm.favorite, c.title!='' named,
    (SELECT count(*) FROM members WHERE conversation_id=c.id) member_count,
    (SELECT json_group_array(name) FROM (SELECT u.name FROM members peers JOIN user u ON u.id=peers.user_id WHERE peers.conversation_id=c.id AND peers.user_id!=cm.user_id ORDER BY peers.joined_at,u.id LIMIT 4)) member_names,
    (SELECT count(*) FROM messages m WHERE m.conversation_id=c.id AND m.state='ready' AND m.author_id!=? AND m.created_at>=cm.joined_at AND NOT EXISTS(SELECT 1 FROM blocks b WHERE b.user_id=cm.user_id AND b.blocked_id=m.author_id) AND NOT EXISTS(SELECT 1 FROM progress p WHERE p.message_id=m.id AND p.user_id=? AND p.watched=1)) unread,
    (SELECT count(*) FROM messages m WHERE m.conversation_id=c.id AND m.state='ready' AND m.kind IN ('video','audio') AND m.author_id!=cm.user_id AND m.created_at>=cm.joined_at AND NOT EXISTS(SELECT 1 FROM blocks b WHERE b.user_id=cm.user_id AND b.blocked_id=m.author_id) AND NOT EXISTS(SELECT 1 FROM progress p WHERE p.message_id=m.id AND p.user_id=cm.user_id AND p.watched=1)) unwatched,
    (SELECT json_object('kind',m.kind,'text',m.text,'author',u.name,'duration_ms',m.duration_ms,'position_ms',COALESCE(p.position_ms,0),'watched',COALESCE(p.watched,0)) FROM messages m JOIN user u ON u.id=m.author_id LEFT JOIN progress p ON p.message_id=m.id AND p.user_id=cm.user_id WHERE m.conversation_id=c.id AND m.state='ready' AND m.created_at>=cm.joined_at AND NOT EXISTS(SELECT 1 FROM blocks b WHERE b.user_id=cm.user_id AND b.blocked_id=m.author_id) ORDER BY m.seq DESC LIMIT 1) preview_data,
    (SELECT text FROM messages m WHERE m.conversation_id=c.id AND m.state='ready' AND m.created_at>=cm.joined_at AND NOT EXISTS(SELECT 1 FROM blocks b WHERE b.user_id=cm.user_id AND b.blocked_id=m.author_id) ORDER BY m.seq DESC LIMIT 1) preview,
    (SELECT MAX(created_at) FROM messages WHERE conversation_id=c.id AND state='ready') last_activity
    FROM conversations c JOIN members cm ON cm.conversation_id=c.id WHERE cm.user_id=? ORDER BY cm.favorite DESC, COALESCE(last_activity,c.created_at) DESC, c.id`,
  )
    .bind(c.get("userId"), c.get("userId"), c.get("userId"))
    .all<{ member_names: string }>();
  return c.json(
    result.results.map((row) => ({
      ...row,
      member_names: JSON.parse(row.member_names) as string[],
    })),
  );
});
conversations.post("/", async (c) => {
  const input = await body(
    c,
    z.object({
      id,
      title: z.string().trim().max(80).optional(),
      invite_id: id.optional(),
      member_ids: z.array(z.string().min(1).max(100)).max(50).optional(),
    }),
  );
  const uid = c.get("userId");
  const now = Date.now();
  const existing = await c.env.DB.prepare(
    "SELECT creator_id FROM conversations WHERE id=?",
  )
    .bind(input.id)
    .first<{ creator_id: string }>();
  if (existing && existing.creator_id !== uid)
    fail(409, "Conversation ID already exists");
  if (existing && (await membership(c.env, input.id, uid)).role !== "owner")
    fail(403, "Only the owner can add people or create invitations");
  const members = [
    ...new Set((input.member_ids ?? []).filter((id) => id !== uid)),
  ];
  for (const memberId of members) {
    if (!(await knownContact(c.env, uid, memberId)))
      fail(403, "You can only add people you already talk with");
  }
  if (input.invite_id) {
    const used = await c.env.DB.prepare(
      "SELECT conversation_id FROM invites WHERE token=?",
    )
      .bind(input.invite_id)
      .first<{ conversation_id: string }>();
    if (used && used.conversation_id !== input.id)
      fail(409, "Invitation ID already exists");
  }
  try {
    await c.env.DB.batch([
      c.env.DB.prepare(
        // A conflicting owner aborts the whole batch, including simultaneous requests.
        "INSERT INTO conversations VALUES(?,?,?,?) ON CONFLICT(id) DO UPDATE SET creator_id=CASE WHEN conversations.creator_id=excluded.creator_id THEN conversations.creator_id ELSE NULL END",
      ).bind(input.id, input.title ?? "", uid, now),
      c.env.DB.prepare(
        "INSERT OR IGNORE INTO members(conversation_id,user_id,joined_at,role) VALUES(?,?,?,'owner')",
      ).bind(input.id, uid, now),
      ...(input.invite_id
        ? [
            c.env.DB.prepare(
              "INSERT INTO invites VALUES(?,?,?,?) ON CONFLICT(token) DO UPDATE SET conversation_id=CASE WHEN invites.conversation_id=excluded.conversation_id THEN invites.conversation_id ELSE NULL END",
            ).bind(input.invite_id, input.id, uid, now + 7 * 86400000),
          ]
        : []),
      ...members.map((memberId) =>
        c.env.DB.prepare(
          "INSERT OR IGNORE INTO members(conversation_id,user_id,joined_at,role) VALUES(?,?,?,'member')",
        ).bind(input.id, memberId, now),
      ),
    ]);
  } catch (error) {
    const conflict = await c.env.DB.prepare(
      "SELECT creator_id FROM conversations WHERE id=?",
    )
      .bind(input.id)
      .first<{ creator_id: string }>();
    const invite = input.invite_id
      ? await c.env.DB.prepare(
          "SELECT conversation_id FROM invites WHERE token=?",
        )
          .bind(input.invite_id)
          .first<{ conversation_id: string }>()
      : null;
    if (
      (conflict && conflict.creator_id !== uid) ||
      (invite && invite.conversation_id !== input.id)
    )
      fail(409, "Conversation or invitation ID already exists");
    throw error;
  }
  return c.json(
    {
      id: input.id,
      ...(input.invite_id
        ? { url: `${c.env.APP_URL}/join/${input.invite_id}` }
        : {}),
    },
    201,
  );
});
conversations.get("/:id", async (c) => {
  const uid = c.get("userId"),
    cid = c.req.param("id");
  await membership(c.env, cid, uid);
  const [members, note] = await Promise.all([
    c.env.DB.prepare(
      "SELECT u.id,u.name,cm.role FROM members cm JOIN user u ON u.id=cm.user_id WHERE cm.conversation_id=?",
    )
      .bind(cid)
      .all(),
    c.env.DB.prepare(
      "SELECT text FROM notes WHERE conversation_id=? AND user_id=?",
    )
      .bind(cid, uid)
      .first(),
  ]);
  return c.json({ members: members.results, note: note?.text ?? "" });
});
conversations.patch("/:id", async (c) => {
  const input = await body(
    c,
    z.object({
      muted: z.boolean().optional(),
      favorite: z.boolean().optional(),
      title: z.string().trim().min(1).max(80).optional(),
    }),
  );
  const cid = c.req.param("id"),
    uid = c.get("userId");
  await membership(c.env, cid, uid);
  // Small groups rename like a shared chat: any member may, and everyone sees it.
  if (input.title !== undefined) {
    await c.env.DB.prepare("UPDATE conversations SET title=? WHERE id=?")
      .bind(input.title, cid)
      .run();
  }
  await c.env.DB.prepare(
    "UPDATE members SET muted=COALESCE(?,muted),favorite=COALESCE(?,favorite) WHERE conversation_id=? AND user_id=?",
  )
    .bind(
      input.muted === undefined ? null : Number(input.muted),
      input.favorite === undefined ? null : Number(input.favorite),
      cid,
      uid,
    )
    .run();
  return c.json({ ok: true });
});
conversations.put("/:id/note", async (c) => {
  const { text } = await body(c, z.object({ text: z.string().max(20000) }));
  const cid = c.req.param("id"),
    uid = c.get("userId");
  await membership(c.env, cid, uid);
  await c.env.DB.prepare(
    "INSERT INTO notes VALUES(?,?,?) ON CONFLICT(conversation_id,user_id) DO UPDATE SET text=excluded.text",
  )
    .bind(cid, uid, text)
    .run();
  return c.json({ ok: true });
});
conversations.post("/:id/invite", async (c) => {
  const cid = c.req.param("id"),
    uid = c.get("userId");
  const member = await membership(c.env, cid, uid);
  if (member.role !== "owner") fail(403, "Only the owner can invite people");
  const token = crypto.randomUUID();
  await c.env.DB.prepare("INSERT INTO invites VALUES(?,?,?,?)")
    .bind(token, cid, uid, Date.now() + 7 * 86400000)
    .run();
  return c.json({ token, url: `${c.env.APP_URL}/join/${token}` });
});
conversations.delete("/:id/members/:userId", async (c) => {
  const cid = c.req.param("id"),
    uid = c.get("userId"),
    target = c.req.param("userId");
  const member = await membership(c.env, cid, uid);
  if (uid !== target && member.role !== "owner")
    fail(403, "Only the owner can remove people");
  const targetMember = await membership(c.env, cid, target);
  if (targetMember.role === "owner")
    fail(409, "The owner cannot leave until ownership is transferred");
  await c.env.DB.prepare(
    "DELETE FROM members WHERE conversation_id=? AND user_id=?",
  )
    .bind(cid, target)
    .run();
  await c.env.ROOMS.get(c.env.ROOMS.idFromName(cid)).fetch(
    "https://room/revoke",
    { method: "POST", body: JSON.stringify({ userId: target }) },
  );
  return c.json({ ok: true });
});
conversations.post("/:id/owner", async (c) => {
  const cid = c.req.param("id"),
    uid = c.get("userId");
  if ((await membership(c.env, cid, uid)).role !== "owner")
    fail(403, "Only the owner can transfer ownership");
  const { user_id } = await body(c, z.object({ user_id: z.string().min(1) }));
  await membership(c.env, cid, user_id);
  await c.env.DB.batch([
    c.env.DB.prepare(
      "UPDATE members SET role='member' WHERE conversation_id=? AND role='owner'",
    ).bind(cid),
    c.env.DB.prepare(
      "UPDATE members SET role='owner' WHERE conversation_id=? AND user_id=?",
    ).bind(cid, user_id),
    c.env.DB.prepare("UPDATE conversations SET creator_id=? WHERE id=?").bind(
      user_id,
      cid,
    ),
  ]);
  return c.json({ ok: true });
});
