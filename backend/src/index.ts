import { Hono } from "hono";
import { HTTPException } from "hono/http-exception";
import { z } from "zod";
import { auth } from "./auth";
import { mobileAuth } from "./mobile-auth";
import { body, fail, membership, rateLimit, eventStatement } from "./access";
import { conversations } from "./conversations";
import { messages } from "./messages";
import { media } from "./media";
import { ai } from "./ai";
import { live } from "./live";
import { account } from "./account";
import { dispatch, runJob, scheduled } from "./jobs";
import type { AppEnv, Env, Job } from "./types";
export { ConversationRoom } from "./realtime";
const app = new Hono<AppEnv>();
app.use("*", async (c, next) => {
  c.set("requestId", crypto.randomUUID());
  c.header("X-Request-ID", c.get("requestId"));
  c.header("X-Content-Type-Options", "nosniff");
  await next();
});
app.onError((error, c) => {
  if (error instanceof HTTPException)
    return c.json({ error: error.message }, error.status);
  console.error(
    JSON.stringify({
      requestId: c.get("requestId"),
      category: error.constructor.name,
      detail: c.env.ENVIRONMENT === "development" ? error.message : undefined,
    }),
  );
  return c.json({ error: "Something went wrong. Please try again." }, 500);
});
app.get("/join/:token", async (c) => {
  const token = z.string().uuid().safeParse(c.req.param("token"));
  if (!token.success) fail(404, "Invitation unavailable");
  const invite = await c.env.DB.prepare(
    "SELECT token FROM invites WHERE token=? AND expires_at>?",
  )
    .bind(token.data, Date.now())
    .first();
  if (!invite) fail(404, "This invitation has expired. Ask for a new link.");
  c.header("Cache-Control", "no-store");
  c.header("Referrer-Policy", "no-referrer");
  c.header(
    "Content-Security-Policy",
    "default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; frame-ancestors 'none'",
  );
  return c.html(
    `<!doctype html><html lang="en"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Join Genoa</title><style>body{background:#f5f3ee;color:#252925;font:18px/1.6 system-ui;margin:0;padding:48px 24px}main{max-width:420px;margin:10vh auto}h1{font:38px Georgia}a{display:block;background:#206f60;color:white;padding:16px;border-radius:16px;text-align:center;text-decoration:none}code{overflow-wrap:anywhere;font-size:14px}</style><main><h1>A conversation is waiting.</h1><p>Open Genoa to join. You can sign in or create an account first.</p><a href="genoa://join/${token.data}">Open Genoa</a><p>If the app doesn’t open, install Genoa, then choose “Join with invitation” and paste this link:</p><code>${c.env.APP_URL}/join/${token.data}</code></main></html>`,
  );
});
app.get("/health", (c) => c.json({ ok: true }));
app.route("/auth/mobile", mobileAuth);
app.on(["POST", "GET"], "/auth/*", async (c) => {
  await rateLimit(
    c.env,
    `auth:${c.req.header("cf-connecting-ip") ?? "local"}`,
    20,
  );
  return auth(c.env).handler(c.req.raw);
});
app.use("/api/*", async (c, next) => {
  const session = await auth(c.env).api.getSession({
    headers: c.req.raw.headers,
  });
  if (!session) fail(401, "Please sign in again");
  c.set("userId", session.user.id);
  await rateLimit(c.env, `api:${session.user.id}`, 300);
  await next();
  if (c.req.method !== "GET")
    c.executionCtx.waitUntil(
      dispatch(c.env).catch(() => console.error("OUTBOX_RETRY_SCHEDULED")),
    );
});
app.get("/api/me", async (c) => {
  const uid = c.get("userId");
  const user = await c.env.DB.prepare(
    "SELECT id,name,email FROM user WHERE id=?",
  )
    .bind(uid)
    .first();
  const profile = await c.env.DB.prepare(
    "SELECT * FROM profiles WHERE user_id=?",
  )
    .bind(uid)
    .first();
  return c.json({
    ...user,
    ai_consent: profile?.ai_consent ?? 0,
    notifications: profile?.notifications ?? 1,
    capabilities: {
      ai: !!c.env.AI,
      push: !!c.env.FCM_SERVICE_ACCOUNT,
      apns: !!c.env.APNS_CREDENTIALS,
      live_fmp4: true,
    },
  });
});
app.patch("/api/me", async (c) => {
  const input = await body(
    c,
    z.object({
      name: z.string().trim().min(1).max(80).optional(),
      ai_consent: z.boolean(),
      notifications: z.boolean(),
    }),
  );
  const uid = c.get("userId");
  await c.env.DB.prepare(
    "INSERT INTO profiles VALUES(?,?,?) ON CONFLICT(user_id) DO UPDATE SET ai_consent=excluded.ai_consent,notifications=excluded.notifications",
  )
    .bind(uid, Number(input.ai_consent), Number(input.notifications))
    .run();
  if (input.name)
    await c.env.DB.prepare("UPDATE user SET name=? WHERE id=?")
      .bind(input.name, uid)
      .run();
  if (!input.ai_consent)
    await c.env.DB.batch([
      c.env.DB.prepare(
        "UPDATE messages SET transcript=NULL,summary=NULL,ai_status='none' WHERE author_id=?",
      ).bind(uid),
      c.env.DB.prepare(
        "DELETE FROM captions WHERE message_id IN (SELECT id FROM messages WHERE author_id=?)",
      ).bind(uid),
      c.env.DB.prepare(
        "UPDATE audio_chunks SET transcript=NULL WHERE message_id IN (SELECT id FROM messages WHERE author_id=?)",
      ).bind(uid),
    ]);
  return c.json({ ok: true });
});
app.get("/api/people", async (c) => {
  const uid = c.get("userId");
  const rows = await c.env.DB.prepare(
    `SELECT DISTINCT u.id,u.name
     FROM user u
     JOIN members theirs ON theirs.user_id=u.id
     JOIN members mine ON mine.conversation_id=theirs.conversation_id AND mine.user_id=?
     WHERE u.id!=?
       AND u.name!='Deleted account'
       AND NOT EXISTS(SELECT 1 FROM blocks WHERE user_id=? AND blocked_id=u.id)
       AND NOT EXISTS(SELECT 1 FROM blocks WHERE user_id=u.id AND blocked_id=?)
     ORDER BY u.name COLLATE NOCASE, u.id`,
  )
    .bind(uid, uid, uid, uid)
    .all();
  return c.json(rows.results);
});
app.post("/api/join", async (c) => {
  const { token } = await body(c, z.object({ token: z.string().uuid() }));
  const invite = await c.env.DB.prepare(
    "SELECT * FROM invites WHERE token=? AND expires_at>?",
  )
    .bind(token, Date.now())
    .first<{ conversation_id: string; creator_id: string }>();
  if (!invite) fail(404, "Invitation expired or unavailable");
  const uid = c.get("userId");
  const blocked = await c.env.DB.prepare(
    "SELECT 1 FROM blocks WHERE (user_id=? AND blocked_id=?) OR (user_id=? AND blocked_id=?)",
  )
    .bind(uid, invite.creator_id, invite.creator_id, uid)
    .first();
  if (blocked) fail(403, "Invitation unavailable");
  await c.env.DB.prepare(
    "INSERT OR IGNORE INTO members(conversation_id,user_id,joined_at) VALUES(?,?,?)",
  )
    .bind(invite.conversation_id, uid, Date.now())
    .run();
  return c.json({ id: invite.conversation_id });
});
app.put("/api/devices", async (c) => {
  const { token, platform } = await body(
    c,
    z.object({
      token: z.string().min(20).max(4096),
      platform: z.enum(["android", "ios", "ios-sandbox"]).default("android"),
    }),
  );
  await c.env.DB.prepare(
    "INSERT INTO devices VALUES(?,?,?) ON CONFLICT(token) DO UPDATE SET user_id=excluded.user_id,platform=excluded.platform",
  )
    .bind(token, c.get("userId"), platform)
    .run();
  return c.json({ ok: true });
});
app.delete("/api/devices/current", async (c) => {
  const { token } = await body(
    c,
    z.object({ token: z.string().min(20).max(4096) }),
  );
  await c.env.DB.prepare("DELETE FROM devices WHERE token=? AND user_id=?")
    .bind(token, c.get("userId"))
    .run();
  return c.json({ ok: true });
});
app.post("/api/blocks", async (c) => {
  const { user_id } = await body(
    c,
    z.object({ user_id: z.string().min(1).max(100) }),
  );
  await c.env.DB.prepare("INSERT OR IGNORE INTO blocks VALUES(?,?)")
    .bind(c.get("userId"), user_id)
    .run();
  return c.json({ ok: true });
});
app.post("/api/reports", async (c) => {
  const { message_id, reason } = await body(
    c,
    z.object({
      message_id: z.string().uuid(),
      reason: z.string().min(1).max(2000),
    }),
  );
  await c.env.DB.prepare("INSERT INTO reports VALUES(?,?,?,?,?)")
    .bind(crypto.randomUUID(), c.get("userId"), message_id, reason, Date.now())
    .run();
  return c.json({ ok: true });
});
app.get("/api/conversations/:id/events", async (c) => {
  const cid = c.req.param("id"),
    uid = c.get("userId");
  await membership(c.env, cid, uid);
  const headers = new Headers(c.req.raw.headers);
  headers.set("X-User-Id", uid);
  headers.set("X-Conversation-Id", cid);
  return c.env.ROOMS.get(c.env.ROOMS.idFromName(cid)).fetch(
    new Request("https://room/connect", { headers }),
  );
});
app.route("/api/conversations", conversations);
app.route("/api", messages);
app.route("/api", media);
app.route("/api", ai);
app.route("/api", live);
app.route("/api", account);
export default {
  fetch: app.fetch,
  scheduled: (_event: ScheduledController, env: Env, ctx: ExecutionContext) =>
    ctx.waitUntil(scheduled(env)),
  async queue(batch: MessageBatch<Job>, env: Env) {
    for (const item of batch.messages) {
      try {
        await runJob(env, item.body);
        item.ack();
      } catch {
        console.error(
          JSON.stringify({ jobId: item.body.id, kind: item.body.kind }),
        );
        if (item.body.kind === "ai" && item.attempts >= 5)
          await env.DB.prepare(
            "UPDATE messages SET ai_status='failed' WHERE id=? AND ai_status='pending'",
          )
            .bind(String(item.body.payload.messageId))
            .run();
        item.retry();
      }
    }
  },
};
