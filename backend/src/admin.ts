import { Hono } from "hono";
import { z } from "zod";
import { auth } from "./auth";
import {
  body,
  deleteMessageStatements,
  fail,
  memberConversations,
  rateLimit,
  revokeLive,
} from "./access";
import { serveObject } from "./media";
import { operationalAlerts } from "./jobs";
import type { AppEnv, Ctx } from "./types";

// Moderation and operations for people listed in `admins`. Everyone else sees a 404, as if it didn't exist.
export const admin = new Hono<AppEnv>();
admin.use("*", async (c, next) => {
  c.header("Cache-Control", "no-store");
  const session = await auth(c.env).api.getSession({ headers: c.req.raw.headers });
  if (!session) fail(404, "Not found");
  const allowed = await c.env.DB.prepare("SELECT 1 FROM admins WHERE user_id=?")
    .bind(session.user.id)
    .first();
  if (!allowed) fail(404, "Not found");
  c.set("userId", session.user.id);
  await rateLimit(c.env, `admin:${session.user.id}`, 600);
  await next();
});

const PAGE = 50;
const before = (c: Ctx) => {
  const value = Number(c.req.query("before") ?? Number.MAX_SAFE_INTEGER);
  if (!Number.isSafeInteger(value) || value < 0) fail(400, "Invalid cursor");
  return value;
};
const next = (rows: { created_at: number }[]) =>
  rows.length === PAGE ? rows[rows.length - 1]!.created_at : null;
function audit(c: Ctx, action: string, targetType: string, targetId: string, note?: string) {
  return c.env.DB.prepare("INSERT INTO admin_audit VALUES(?,?,?,?,?,?,?)").bind(
    crypto.randomUUID(),
    c.get("userId"),
    action,
    targetType,
    targetId,
    note ?? null,
    Date.now(),
  );
}
const MESSAGE_FIELDS = `m.id,m.kind,m.state,m.text,m.created_at,m.duration_ms,m.conversation_id,
  (SELECT id FROM assets WHERE message_id=m.id AND purpose='original') asset_id,
  (SELECT mime FROM assets WHERE message_id=m.id AND purpose='original') mime`;
const USER_FIELDS = `u.id,u.name,u.email,u.createdAt,s.created_at suspended_at,s.reason suspension_reason,
  (SELECT COUNT(*) FROM messages WHERE author_id=u.id AND state!='deleted') message_count,
  (SELECT COALESCE(SUM(a.bytes),0) FROM assets a JOIN messages m ON m.id=a.message_id WHERE m.author_id=u.id AND m.state!='deleted') storage_bytes,
  (SELECT COUNT(*) FROM reports r JOIN messages m ON m.id=r.message_id WHERE m.author_id=u.id) report_count`;

async function suspend(c: Ctx, uid: string, reason: string) {
  const rooms = await memberConversations(c.env, uid);
  await c.env.DB.batch([
    c.env.DB.prepare(
      "INSERT INTO suspensions VALUES(?,?,?,?) ON CONFLICT(user_id) DO UPDATE SET reason=excluded.reason",
    ).bind(uid, reason, c.get("userId"), Date.now()),
    c.env.DB.prepare("DELETE FROM session WHERE userId=?").bind(uid),
    c.env.DB.prepare("DELETE FROM devices WHERE user_id=?").bind(uid),
    audit(c, "suspend", "user", uid, reason),
  ]);
  await revokeLive(c.env, uid, rooms);
}
async function removeMessage(c: Ctx, mid: string, note?: string) {
  const exists = await c.env.DB.prepare("SELECT 1 FROM messages WHERE id=?").bind(mid).first();
  if (!exists) fail(404, "Message unavailable");
  await c.env.DB.batch([
    ...deleteMessageStatements(c.env, mid),
    audit(c, "remove_message", "message", mid, note),
  ]);
}

admin.get("/me", async (c) => {
  const user = await c.env.DB.prepare("SELECT id,name,email FROM user WHERE id=?")
    .bind(c.get("userId"))
    .first();
  return c.json(user);
});
admin.get("/overview", async (c) => {
  const day = Date.now() - 86400000;
  const row = await c.env.DB.prepare(
    `SELECT (SELECT COUNT(*) FROM user WHERE name!='Deleted account') users,
      (SELECT COUNT(*) FROM messages WHERE created_at>?1) messages_24h,
      (SELECT COALESCE(SUM(bytes),0) FROM assets) storage_bytes,
      (SELECT COUNT(*) FROM reports WHERE status='open') open_reports,
      (SELECT MIN(created_at) FROM reports WHERE status='open') oldest_open_report_at,
      (SELECT COUNT(*) FROM failed_jobs) failed_jobs,
      (SELECT COUNT(*) FROM outbox WHERE delivered_at IS NULL) outbox_pending,
      (SELECT MIN(created_at) FROM outbox WHERE delivered_at IS NULL) oldest_outbox_at,
      (SELECT COUNT(*) FROM diagnostics WHERE created_at>?1) diagnostics_24h,
      (SELECT COUNT(*) FROM suspensions) suspended_users`,
  )
    .bind(day)
    .first();
  return c.json({ ...row, alerts: await operationalAlerts(c.env) });
});

admin.get("/reports", async (c) => {
  const status = c.req.query("status") === "resolved" ? "resolved" : "open";
  const rows = await c.env.DB.prepare(
    `SELECT r.id,r.reason,r.created_at,r.status,r.resolution,r.note,r.resolved_at,
      json_object('id',ru.id,'name',ru.name,'email',ru.email) reporter,
      json_object('id',m.id,'kind',m.kind,'state',m.state,'text',m.text,'created_at',m.created_at,'duration_ms',m.duration_ms,
        'conversation_id',m.conversation_id,
        'asset_id',(SELECT id FROM assets WHERE message_id=m.id AND purpose='original'),
        'mime',(SELECT mime FROM assets WHERE message_id=m.id AND purpose='original')) message,
      json_object('id',au.id,'name',au.name,'email',au.email,'suspended_at',(SELECT created_at FROM suspensions WHERE user_id=au.id)) author,
      (SELECT COUNT(*) FROM reports r2 JOIN messages m2 ON m2.id=r2.message_id WHERE m2.author_id=au.id) reports_against_author
     FROM reports r JOIN user ru ON ru.id=r.user_id JOIN messages m ON m.id=r.message_id JOIN user au ON au.id=m.author_id
     WHERE r.status=? AND r.created_at<? ORDER BY r.created_at DESC LIMIT ${PAGE}`,
  )
    .bind(status, before(c))
    .all<Record<string, unknown> & { created_at: number }>();
  const reports = rows.results.map((r) => ({
    ...r,
    reporter: JSON.parse(String(r.reporter)),
    message: JSON.parse(String(r.message)),
    author: JSON.parse(String(r.author)),
  }));
  return c.json({ reports, next_before: next(rows.results) });
});
admin.post("/reports/:id/resolve", async (c) => {
  const input = await body(
    c,
    z.object({
      action: z.enum(["dismiss", "remove_message", "remove_and_suspend"]),
      note: z.string().trim().max(2000).optional(),
    }),
  );
  const report = await c.env.DB.prepare(
    "SELECT r.message_id,m.author_id FROM reports r JOIN messages m ON m.id=r.message_id WHERE r.id=?",
  )
    .bind(c.req.param("id"))
    .first<{ message_id: string; author_id: string }>();
  if (!report) fail(404, "Report unavailable");
  if (input.action === "remove_and_suspend" && !input.note)
    fail(400, "Give a reason for the suspension");
  if (input.action !== "dismiss") await removeMessage(c, report.message_id, input.note);
  if (input.action === "remove_and_suspend") await suspend(c, report.author_id, input.note!);
  const resolution = {
    dismiss: "dismissed",
    remove_message: "removed",
    remove_and_suspend: "removed_and_suspended",
  }[input.action];
  await c.env.DB.batch([
    c.env.DB.prepare(
      "UPDATE reports SET status='resolved',resolution=?,note=?,resolved_at=?,resolved_by=? WHERE message_id=? AND status='open'",
    ).bind(resolution, input.note ?? null, Date.now(), c.get("userId"), report.message_id),
    audit(c, `report_${resolution}`, "report", c.req.param("id"), input.note),
  ]);
  return c.json({ ok: true });
});

admin.get("/users", async (c) => {
  const q = (c.req.query("q") ?? "").trim().toLowerCase().slice(0, 100);
  // instr, not LIKE: D1 rejects LIKE patterns over 50 bytes, which a full email address can exceed.
  const rows = await c.env.DB.prepare(
    `SELECT ${USER_FIELDS} FROM user u LEFT JOIN suspensions s ON s.user_id=u.id
     WHERE instr(lower(u.email),?1)>0 OR instr(lower(u.name),?1)>0 OR u.id=?1
     ORDER BY u.createdAt DESC LIMIT ${PAGE}`,
  )
    .bind(q)
    .all();
  return c.json({ users: rows.results });
});
admin.get("/users/:id", async (c) => {
  const uid = c.req.param("id");
  const user = await c.env.DB.prepare(
    `SELECT ${USER_FIELDS} FROM user u LEFT JOIN suspensions s ON s.user_id=u.id WHERE u.id=?`,
  )
    .bind(uid)
    .first();
  if (!user) fail(404, "User unavailable");
  const [messages, reports] = await Promise.all([
    c.env.DB.prepare(
      `SELECT ${MESSAGE_FIELDS} FROM messages m WHERE m.author_id=? ORDER BY m.created_at DESC LIMIT ${PAGE}`,
    )
      .bind(uid)
      .all(),
    c.env.DB.prepare(
      `SELECT r.id,r.reason,r.created_at,r.status,r.resolution,r.message_id FROM reports r JOIN messages m ON m.id=r.message_id
       WHERE m.author_id=? ORDER BY r.created_at DESC LIMIT ${PAGE}`,
    )
      .bind(uid)
      .all(),
  ]);
  return c.json({ user, messages: messages.results, reports: reports.results });
});
admin.post("/users/:id/suspend", async (c) => {
  const { reason } = await body(c, z.object({ reason: z.string().trim().min(1).max(2000) }));
  const uid = c.req.param("id");
  if (uid === c.get("userId")) fail(409, "You can't suspend your own account");
  if (!(await c.env.DB.prepare("SELECT 1 FROM user WHERE id=?").bind(uid).first()))
    fail(404, "User unavailable");
  await suspend(c, uid, reason);
  return c.json({ ok: true });
});
admin.post("/users/:id/unsuspend", async (c) => {
  const uid = c.req.param("id");
  await c.env.DB.batch([
    c.env.DB.prepare("DELETE FROM suspensions WHERE user_id=?").bind(uid),
    audit(c, "unsuspend", "user", uid),
  ]);
  return c.json({ ok: true });
});
admin.post("/messages/:id/remove", async (c) => {
  const { note } = await body(c, z.object({ note: z.string().trim().max(2000).optional() }));
  await removeMessage(c, c.req.param("id"), note);
  return c.json({ ok: true });
});
admin.get("/media/:id", async (c) => {
  const asset = await c.env.DB.prepare(
    "SELECT a.object_key,a.mime FROM assets a JOIN messages m ON m.id=a.message_id WHERE a.id=? AND m.state!='deleted'",
  )
    .bind(c.req.param("id"))
    .first<{ object_key: string; mime: string }>();
  if (!asset) fail(404, "Media unavailable");
  return serveObject(c, asset.object_key, asset.mime);
});

admin.get("/jobs/failed", async (c) => {
  const rows = await c.env.DB.prepare(
    "SELECT * FROM failed_jobs ORDER BY failed_at DESC LIMIT 200",
  ).all<{ payload: string }>();
  return c.json({
    jobs: rows.results.map((j) => ({ ...j, payload: JSON.parse(j.payload) })),
  });
});
admin.post("/jobs/:id/retry", async (c) => {
  const job = await c.env.DB.prepare("SELECT * FROM failed_jobs WHERE id=?")
    .bind(c.req.param("id"))
    .first<{ id: string; kind: string; payload: string }>();
  if (!job) fail(404, "Job unavailable");
  await c.env.JOBS.send({ id: job.id, kind: job.kind, payload: JSON.parse(job.payload) });
  await c.env.DB.batch([
    c.env.DB.prepare("DELETE FROM failed_jobs WHERE id=?").bind(job.id),
    audit(c, "retry_job", "job", job.id),
  ]);
  return c.json({ ok: true });
});
admin.post("/jobs/:id/discard", async (c) => {
  await c.env.DB.batch([
    c.env.DB.prepare("DELETE FROM failed_jobs WHERE id=?").bind(c.req.param("id")),
    audit(c, "discard_job", "job", c.req.param("id")),
  ]);
  return c.json({ ok: true });
});

admin.get("/diagnostics", async (c) => {
  const rows = await c.env.DB.prepare(
    `SELECT * FROM diagnostics WHERE created_at<? ORDER BY created_at DESC LIMIT ${PAGE}`,
  )
    .bind(before(c))
    .all<{ created_at: number }>();
  return c.json({ diagnostics: rows.results, next_before: next(rows.results) });
});
admin.get("/diagnostics/:id", async (c) => {
  const row = await c.env.DB.prepare("SELECT * FROM diagnostics WHERE id=?")
    .bind(c.req.param("id"))
    .first<{ id: string }>();
  if (!row) fail(404, "Report unavailable");
  const object = await c.env.MEDIA.get(`diagnostics/${row.id}.json`);
  return c.json({ ...row, payload: object ? await object.json() : null });
});

admin.get("/audit", async (c) => {
  const rows = await c.env.DB.prepare(
    `SELECT a.*,u.email admin_email FROM admin_audit a LEFT JOIN user u ON u.id=a.admin_id
     WHERE a.created_at<? ORDER BY a.created_at DESC LIMIT ${PAGE}`,
  )
    .bind(before(c))
    .all<{ created_at: number }>();
  return c.json({ entries: rows.results, next_before: next(rows.results) });
});
