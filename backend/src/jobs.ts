import type { Env, Job, Message } from "./types";
import { transcribe } from "./transcription";
import { Buffer } from "node:buffer";
import { sendApplePush } from "./apple-push";
import { sendEmail } from "./email";
let fcmToken: { token: string; expires: number } | undefined;
const b64 = (value: string | Uint8Array) =>
  Buffer.from(value).toString("base64url");
async function googleToken(env: Env) {
  if (!env.FCM_SERVICE_ACCOUNT) return null;
  const account = JSON.parse(env.FCM_SERVICE_ACCOUNT) as {
    client_email: string;
    private_key: string;
    project_id: string;
  };
  if (fcmToken && fcmToken.expires > Date.now())
    return { token: fcmToken.token, project: account.project_id };
  const now = Math.floor(Date.now() / 1000),
    payload = `${b64(JSON.stringify({ alg: "RS256", typ: "JWT" }))}.${b64(JSON.stringify({ iss: account.client_email, scope: "https://www.googleapis.com/auth/firebase.messaging", aud: "https://oauth2.googleapis.com/token", iat: now, exp: now + 3600 }))}`;
  const pem = account.private_key
    .replace(/-----[^-]+-----/g, "")
    .replace(/\s/g, "");
  const key = await crypto.subtle.importKey(
    "pkcs8",
    Uint8Array.from(atob(pem), (c) => c.charCodeAt(0)),
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
    false,
    ["sign"],
  );
  const signature = await crypto.subtle.sign(
    "RSASSA-PKCS1-v1_5",
    key,
    new TextEncoder().encode(payload),
  );
  const response = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST",
    body: new URLSearchParams({
      grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
      assertion: `${payload}.${b64(new Uint8Array(signature))}`,
    }),
  });
  if (!response.ok) throw new Error("PUSH_AUTH_FAILED");
  const result = await response.json<{ access_token: string }>();
  fcmToken = { token: result.access_token, expires: Date.now() + 3300000 };
  return { token: result.access_token, project: account.project_id };
}
async function push(
  env: Env,
  uid: string,
  title: string,
  body: string,
  cid: string,
  eventId: string,
  messageId: string,
) {
  const credential = await googleToken(env);
  const devices = await env.DB.prepare(
    "SELECT token,platform FROM devices WHERE user_id=?",
  )
    .bind(uid)
    .all<{ token: string; platform?: string }>();
  for (const device of devices.results) {
    if (device.platform === "ios" || device.platform === "ios-sandbox") {
      await sendApplePush(env, device, {
        title,
        body,
        conversation_id: cid,
        message_id: messageId,
        event_id: eventId,
      });
      continue;
    }
    if (!credential) continue;
    const r = await fetch(
      `https://fcm.googleapis.com/v1/projects/${credential.project}/messages:send`,
      {
        method: "POST",
        headers: {
          Authorization: `Bearer ${credential.token}`,
          "Content-Type": "application/json",
        },
        body: JSON.stringify({
          message: {
            token: device.token,
            notification: { title, body },
            data: {
              conversation_id: cid,
              message_id: messageId,
              event_id: eventId,
            },
            android: { collapse_key: eventId, notification: { tag: eventId } },
          },
        }),
      },
    );
    if (r.status === 404)
      await env.DB.prepare("DELETE FROM devices WHERE token=?")
        .bind(device.token)
        .run();
    else if (!r.ok) throw new Error("PUSH_DELIVERY_FAILED");
  }
}
export async function runJob(env: Env, job: Job) {
  const mid = String(job.payload.messageId ?? "");
  if (job.kind === "delete") {
    await env.MEDIA.delete(`media/${mid}/live/init.mp4`);
    const assets = await env.DB.prepare(
      "SELECT object_key FROM assets WHERE message_id=?",
    )
      .bind(mid)
      .all<{ object_key: string }>();
    const segments = await env.DB.prepare(
      "SELECT object_key FROM live_segments WHERE message_id=?",
    )
      .bind(mid)
      .all<{ object_key: string }>();
    const audio = await env.DB.prepare(
      "SELECT object_key FROM audio_chunks WHERE message_id=?",
    )
      .bind(mid)
      .all<{ object_key: string }>();
    for (const asset of [
      ...assets.results,
      ...segments.results,
      ...audio.results,
    ])
      await env.MEDIA.delete(asset.object_key);
    const uploads = await env.DB.prepare(
      "SELECT object_key,upload_id,state FROM uploads WHERE message_id=?",
    )
      .bind(mid)
      .all<{ object_key: string; upload_id: string; state: string }>();
    for (const u of uploads.results) {
      if (u.state === "open")
        await env.MEDIA.resumeMultipartUpload(u.object_key, u.upload_id)
          .abort()
          .catch(() => {});
      await env.MEDIA.delete(u.object_key);
    }
    await env.DB.batch([
      env.DB.prepare("DELETE FROM assets WHERE message_id=?").bind(mid),
      env.DB.prepare("DELETE FROM live_segments WHERE message_id=?").bind(mid),
      env.DB.prepare("DELETE FROM audio_chunks WHERE message_id=?").bind(mid),
      env.DB.prepare("DELETE FROM captions WHERE message_id=?").bind(mid),
      env.DB.prepare("DELETE FROM reactions WHERE message_id=?").bind(mid),
      env.DB.prepare("DELETE FROM reaction_moments WHERE message_id=?").bind(
        mid,
      ),
    ]);
    return;
  }
  const m = await env.DB.prepare(
    "SELECT * FROM messages WHERE id=? AND state='ready'",
  )
    .bind(mid)
    .first<Message>();
  if (!m) return;
  if (job.kind === "message") {
    await env.ROOMS.get(env.ROOMS.idFromName(m.conversation_id)).fetch(
      "https://room/event",
      { method: "POST", body: JSON.stringify({ type: "changed" }) },
    );
    const members = await env.DB.prepare(
      `SELECT cm.user_id FROM members cm LEFT JOIN profiles p ON p.user_id=cm.user_id WHERE cm.conversation_id=? AND cm.user_id!=? AND cm.muted=0 AND COALESCE(p.notifications,1)=1 AND NOT EXISTS(SELECT 1 FROM blocks b WHERE b.user_id=cm.user_id AND b.blocked_id=?)`,
    )
      .bind(m.conversation_id, m.author_id, m.author_id)
      .all<{ user_id: string }>();
    for (const member of members.results)
      await push(
        env,
        member.user_id,
        "Genoa",
        m.urgent ? "An urgent note is waiting" : "A new message is waiting",
        m.conversation_id,
        job.id,
        m.id,
      );
  } else if (job.kind === "reminder") {
    const allowed = await env.DB.prepare(
      "SELECT 1 FROM members WHERE conversation_id=? AND user_id=?",
    )
      .bind(m.conversation_id, String(job.payload.userId))
      .first();
    if (allowed)
      await push(
        env,
        String(job.payload.userId),
        "Time to reply",
        "Your saved reminder is ready",
        m.conversation_id,
        job.id,
        m.id,
      );
  } else if (job.kind === "ai") await transcribe(env, m);
}
export async function dispatch(env: Env) {
  const rows = await env.DB.prepare(
    "SELECT * FROM outbox WHERE delivered_at IS NULL ORDER BY created_at LIMIT 50",
  ).all<{ id: string; kind: string; payload: string }>();
  for (const row of rows.results) {
    await env.JOBS.send({
      id: row.id,
      kind: row.kind,
      payload: JSON.parse(row.payload),
    });
    await env.DB.prepare("UPDATE outbox SET delivered_at=? WHERE id=?")
      .bind(Date.now(), row.id)
      .run();
  }
}
export async function scheduled(env: Env) {
  const stale = await env.DB.prepare(
    "SELECT id FROM messages WHERE state='live' AND live_activity<? LIMIT 50",
  )
    .bind(Date.now() - 120000)
    .all<{ id: string }>();
  for (const row of stale.results)
    await env.DB.batch([
      env.DB.prepare(
        "UPDATE messages SET state=CASE WHEN EXISTS(SELECT 1 FROM live_segments WHERE message_id=?) THEN 'ready' ELSE 'failed' END,duration_ms=COALESCE((SELECT SUM(duration)*1000 FROM live_segments WHERE message_id=?),0) WHERE id=? AND state='live' AND live_activity<?",
      ).bind(row.id, row.id, row.id, Date.now() - 120000),
      env.DB.prepare(
        "INSERT OR IGNORE INTO outbox(id,kind,payload,created_at) VALUES(?,'message',?,?)",
      ).bind(
        `message:${row.id}`,
        JSON.stringify({ messageId: row.id }),
        Date.now(),
      ),
    ]);
  const abandoned = await env.DB.prepare(
    "SELECT id,object_key,upload_id FROM uploads WHERE state='open' AND created_at<? LIMIT 25",
  )
    .bind(Date.now() - 7 * 86400000)
    .all<{ id: string; object_key: string; upload_id: string }>();
  for (const upload of abandoned.results) {
    await env.MEDIA.resumeMultipartUpload(upload.object_key, upload.upload_id)
      .abort()
      .catch(() => {});
    await env.DB.prepare(
      "UPDATE uploads SET state='expired' WHERE id=? AND state='open'",
    )
      .bind(upload.id)
      .run();
  }

  const reminders = await env.DB.prepare(
    "SELECT * FROM reminders WHERE sent=0 AND due_at<=?",
  )
    .bind(Date.now())
    .all<{ id: string; message_id: string; user_id: string }>();
  for (const r of reminders.results)
    await env.DB.batch([
      env.DB.prepare("INSERT OR IGNORE INTO outbox VALUES(?,?,?,?,NULL)").bind(
        `reminder:${r.id}`,
        "reminder",
        JSON.stringify({ messageId: r.message_id, userId: r.user_id }),
        Date.now(),
      ),
      env.DB.prepare("UPDATE reminders SET sent=1 WHERE id=?").bind(r.id),
    ]);
  await dispatch(env);
  await env.DB.prepare("DELETE FROM rate_limits WHERE window<?")
    .bind(Math.floor(Date.now() / 60000) - 60)
    .run();
  await pruneDiagnostics(env);
  await sweepOrphanedMedia(env);
  await checkAlerts(env);
}

export async function recordFailedJob(env: Env, job: Job, attempts: number) {
  await env.DB.prepare(
    "INSERT INTO failed_jobs VALUES(?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET attempts=excluded.attempts,failed_at=excluded.failed_at",
  )
    .bind(job.id, job.kind, JSON.stringify(job.payload), attempts, Date.now())
    .run();
  console.error(JSON.stringify({ category: "JOB_DEAD_LETTERED", jobId: job.id, kind: job.kind }));
}

async function maintenanceValue(env: Env, key: string) {
  const row = await env.DB.prepare("SELECT value FROM maintenance WHERE key=?")
    .bind(key)
    .first<{ value: string }>();
  return row?.value ?? null;
}
async function setMaintenance(env: Env, key: string, value: string) {
  await env.DB.prepare(
    "INSERT INTO maintenance VALUES(?,?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value,updated_at=excluded.updated_at",
  )
    .bind(key, value, Date.now())
    .run();
}

const DIAGNOSTIC_RETENTION = 30 * 86400000;
async function pruneDiagnostics(env: Env) {
  const old = await env.DB.prepare(
    "SELECT id FROM diagnostics WHERE created_at<? LIMIT 100",
  )
    .bind(Date.now() - DIAGNOSTIC_RETENTION)
    .all<{ id: string }>();
  for (const row of old.results) {
    await env.MEDIA.delete(`diagnostics/${row.id}.json`);
    await env.DB.prepare("DELETE FROM diagnostics WHERE id=?").bind(row.id).run();
  }
}

// Reconciles R2 against D1 a page at a time. Anything written after its message was deleted, or never
// recorded because a write raced a state change, is removed once it is old enough not to be in flight.
const ORPHAN_AGE = 86400000;
export async function sweepOrphanedMedia(env: Env, now = Date.now()) {
  const cursor = await maintenanceValue(env, "media_sweep_cursor");
  const page = await env.MEDIA.list({
    prefix: "media/",
    limit: 200,
    ...(cursor ? { cursor } : {}),
  });
  let removed = 0;
  for (const object of page.objects) {
    const mid = object.key.split("/")[1] ?? "";
    const message = await env.DB.prepare("SELECT state FROM messages WHERE id=?")
      .bind(mid)
      .first<{ state: string }>();
    const deleted = !message || message.state === "deleted";
    if (!deleted && object.key === `media/${mid}/live/init.mp4`) continue;
    const referenced =
      !deleted &&
      (await env.DB.prepare(
        `SELECT 1 FROM assets WHERE object_key=?1 UNION ALL SELECT 1 FROM live_segments WHERE object_key=?1
         UNION ALL SELECT 1 FROM audio_chunks WHERE object_key=?1 UNION ALL SELECT 1 FROM uploads WHERE object_key=?1 AND state!='expired' LIMIT 1`,
      )
        .bind(object.key)
        .first());
    if (referenced || now - object.uploaded.getTime() < ORPHAN_AGE) continue;
    await env.MEDIA.delete(object.key);
    removed++;
  }
  if (removed)
    console.log(JSON.stringify({ category: "ORPHANED_MEDIA_REMOVED", count: removed }));
  await setMaintenance(
    env,
    "media_sweep_cursor",
    page.truncated ? page.cursor : "",
  );
  return removed;
}

const ALERT_INTERVAL = 6 * 3600000;
export async function operationalAlerts(env: Env, now = Date.now()) {
  const [failed, report, outbox] = await Promise.all([
    env.DB.prepare("SELECT COUNT(*) n FROM failed_jobs").first<{ n: number }>(),
    env.DB.prepare(
      "SELECT MIN(created_at) oldest FROM reports WHERE status='open'",
    ).first<{ oldest: number | null }>(),
    env.DB.prepare(
      "SELECT MIN(created_at) oldest FROM outbox WHERE delivered_at IS NULL",
    ).first<{ oldest: number | null }>(),
  ]);
  const alerts: { key: string; text: string }[] = [];
  if (failed?.n)
    alerts.push({ key: "failed_jobs", text: `${failed.n} background job(s) exhausted their retries.` });
  if (report?.oldest && now - report.oldest > 24 * 3600000)
    alerts.push({ key: "reports", text: "A user report has been waiting for review for more than 24 hours." });
  if (outbox?.oldest && now - outbox.oldest > 15 * 60000)
    alerts.push({ key: "outbox", text: "Notifications or deletions have been queued for more than 15 minutes." });
  return alerts;
}
async function checkAlerts(env: Env) {
  const alerts = await operationalAlerts(env);
  for (const alert of alerts) {
    console.error(JSON.stringify({ category: "OPERATIONAL_ALERT", alert: alert.key }));
    if (!env.ALERT_EMAIL) continue;
    const last = Number((await maintenanceValue(env, `alert:${alert.key}`)) ?? 0);
    if (Date.now() - last < ALERT_INTERVAL) continue;
    await sendEmail(env, {
      to: env.ALERT_EMAIL,
      subject: `Genoa alert: ${alert.key.replace("_", " ")}`,
      text: `${alert.text}\n\nReview it in the Genoa admin console.`,
    })
      .then(() => setMaintenance(env, `alert:${alert.key}`, String(Date.now())))
      .catch(() => console.error(JSON.stringify({ category: "ALERT_EMAIL_FAILED" })));
  }
}
