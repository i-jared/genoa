import { localFetch as fetch } from "./http";
import { test } from "node:test";
import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { existsSync, readdirSync, readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { DatabaseSync } from "node:sqlite";

// Exercises moderation, recovery, limits and deletion races against the local dev server. It reads the
// local D1/R2 SQLite files to seed an admin and to prove objects are gone, so it never runs remotely.
const base = process.env.TEST_API_URL ?? "http://localhost:8787";
const here = dirname(fileURLToPath(import.meta.url));
const state = join(here, "..", ".wrangler", "state", "v3");
const local =
  ["localhost", "127.0.0.1"].includes(new URL(base).hostname) && existsSync(state);
const sqliteFiles = (dir: string) =>
  existsSync(dir)
    ? readdirSync(dir)
        .filter((f) => f.endsWith(".sqlite") && f !== "metadata.sqlite")
        .map((f) => join(dir, f))
    : [];
function open(path: string) {
  const db = new DatabaseSync(path);
  db.exec("PRAGMA busy_timeout = 10000");
  return db;
}
function hasTable(path: string, table: string) {
  const db = open(path);
  try {
    return !!db.prepare("SELECT 1 FROM sqlite_master WHERE name=?").get(table);
  } finally {
    db.close();
  }
}
const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));
async function eventually(check: () => boolean | Promise<boolean>, what: string, ms = 20000) {
  const until = Date.now() + ms;
  while (Date.now() < until) {
    if (await check()) return;
    await sleep(250);
  }
  assert.fail(`Timed out waiting for ${what}`);
}

test("moderation, recovery, limits and deletion races", { skip: !local }, async (t) => {
  const d1 = open(sqliteFiles(join(state, "d1", "miniflare-D1DatabaseObject")).find((f) => hasTable(f, "admins"))!);
  const r2 = sqliteFiles(join(state, "r2", "miniflare-R2BucketObject")).filter((f) => hasTable(f, "_mf_objects"));
  const stored = (key: string) =>
    r2.some((path) => {
      const db = open(path);
      try {
        return !!db.prepare("SELECT 1 FROM _mf_objects WHERE key=?").get(key);
      } finally {
        db.close();
      }
    });
  const tokens: string[] = [];
  t.after(async () => {
    for (const token of tokens)
      await request("/api/account/delete", "POST", { confirmation: "DELETE" }, token);
    d1.close();
  });
  async function request(
    path: string,
    method = "GET",
    value?: unknown,
    token?: string,
    headers: Record<string, string> = {},
  ) {
    const raw = value instanceof Uint8Array || typeof value === "string";
    return fetch(base + path, {
      method,
      headers: {
        Origin: base,
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
        ...(value && !raw ? { "Content-Type": "application/json" } : {}),
        ...headers,
      },
      body: raw ? (value as BodyInit) : value ? JSON.stringify(value) : undefined,
    });
  }
  async function account(name: string, password = "correct-horse-1") {
    const email = `${randomUUID()}@example.test`;
    const response = await request("/auth/sign-up/email", "POST", { name, email, password });
    assert.equal(response.status, 200, await response.clone().text());
    const token = response.headers.get("set-auth-token")!;
    tokens.push(token);
    const { user } = (await response.json()) as { user: { id: string } };
    return { token, id: user.id, email, password };
  }
  const signIn = (email: string, password: string, origin = base) =>
    request("/auth/sign-in/email", "POST", { email, password }, undefined, { Origin: origin });
  const fixture = (name: string) => new Uint8Array(readFileSync(join(here, "fixtures", name)));
  async function upload(
    token: string,
    cid: string,
    bytes: Uint8Array,
    kind: "video" | "audio",
    complete: Record<string, string> = {},
  ) {
    const id = randomUUID();
    const opened = await request(
      "/api/uploads",
      "POST",
      { id, conversation_id: cid, kind, mime: `${kind}/mp4`, bytes: bytes.length, duration_ms: 1000 },
      token,
    );
    assert.equal(opened.status, 201, await opened.clone().text());
    const part = await request(`/api/uploads/${id}/parts/1`, "PUT", bytes, token);
    assert.equal(part.status, 200, await part.clone().text());
    return { id, done: request(`/api/uploads/${id}/complete`, "POST", {}, token, complete) };
  }
  function socket(token: string, cid: string) {
    const ws = new WebSocket(`${base.replace("http", "ws")}/api/conversations/${cid}/events`, {
      headers: { Authorization: `Bearer ${token}` },
    } as never);
    const closed = new Promise<number>((resolve) => ws.addEventListener("close", (e) => resolve(e.code)));
    const opened = new Promise<void>((resolve, reject) => {
      ws.addEventListener("open", () => resolve());
      ws.addEventListener("error", () => reject(new Error("socket failed")));
    });
    return { ws, closed, opened };
  }

  const alice = await account("Alice"),
    bob = await account("Bob"),
    eve = await account("Eve"),
    mod = await account("Moderator");
  const cid = randomUUID();
  assert.equal((await request("/api/conversations", "POST", { id: cid }, alice.token)).status, 201);
  const invite = (await (await request(`/api/conversations/${cid}/invite`, "POST", {}, alice.token)).json()) as {
    token: string;
  };
  assert.equal((await request("/api/join", "POST", { token: invite.token }, bob.token)).status, 200);

  await t.test("uploads must contain a playable track, and rejection removes the object", async () => {
    const video = await upload(alice.token, cid, fixture("tiny-video.mp4"), "video");
    assert.equal((await video.done).status, 200);
    const voiceAsVideo = await upload(alice.token, cid, fixture("synthetic-speech.m4a"), "video");
    const rejected = await voiceAsVideo.done;
    assert.equal(rejected.status, 422);
    assert.equal(((await rejected.json()) as { code: string }).code, "unsupported_media");
    assert.ok(!stored(`media/${voiceAsVideo.id}/original`));
    // Retrying is safe and stays terminal rather than failing differently.
    const retry = await request(`/api/uploads/${voiceAsVideo.id}/complete`, "POST", {}, alice.token);
    assert.equal(retry.status, 422);
    const fake = new Uint8Array(4096);
    fake.set(new TextEncoder().encode("\0\0\0\x10ftypisom"));
    assert.equal((await (await upload(alice.token, cid, fake, "audio")).done).status, 422);
  });

  for (const hold of [1500, 9000]) {
    await t.test(`deleting a message during upload completion leaves nothing behind (${hold} ms)`, async () => {
      const pending = await upload(bob.token, cid, fixture("synthetic-speech.m4a"), "audio", {
        "X-Test-Hold-Ms": String(hold),
      });
      await sleep(300);
      assert.equal((await request(`/api/messages/${pending.id}`, "DELETE", undefined, bob.token)).status, 200);
      const done = await pending.done;
      assert.equal(done.status, 404, await done.clone().text());
      await eventually(
        () =>
          !stored(`media/${pending.id}/original`) &&
          !d1.prepare("SELECT 1 FROM assets WHERE message_id=?").get(pending.id),
        "the deleted upload's object and asset row to disappear",
      );
      const watch = (await (await request(`/api/conversations/${cid}/messages`, "GET", undefined, alice.token)).json()) as {
        id: string;
      }[];
      assert.ok(!watch.some((m) => m.id === pending.id));
    });
  }

  await t.test("a delete that arrives before its message makes 404 final", async () => {
    const early = randomUUID();
    assert.equal((await request(`/api/messages/${early}`, "DELETE", undefined, bob.token)).status, 404);
    const media = await request(
      "/api/uploads",
      "POST",
      { id: early, conversation_id: cid, kind: "audio", mime: "audio/mp4", bytes: 1000, duration_ms: 1000 },
      bob.token,
    );
    assert.equal(media.status, 410);
    assert.equal(((await media.json()) as { code: string }).code, "message_discarded");
    const text = await request(`/api/conversations/${cid}/messages`, "POST", { id: early, text: "late" }, bob.token);
    assert.equal(text.status, 410);
    const live = await request(`/api/conversations/${cid}/live`, "POST", { id: early }, bob.token);
    assert.equal(live.status, 410, await live.clone().text());
    assert.ok(!d1.prepare("SELECT 1 FROM messages WHERE id=?").get(early));
  });

  await t.test("per-account storage quota counts uploads in progress", async () => {
    const quotaUser = await account("Quota");
    const qcid = randomUUID();
    await request("/api/conversations", "POST", { id: qcid }, quotaUser.token);
    const open = (id: string) =>
      request(
        "/api/uploads",
        "POST",
        { id, conversation_id: qcid, kind: "video", mime: "video/mp4", bytes: 8 * 1024 ** 3, duration_ms: 1000 },
        quotaUser.token,
      );
    for (let i = 0; i < 3; i++) assert.equal((await open(randomUUID())).status, 201);
    const over = await open(randomUUID());
    assert.equal(over.status, 413);
    assert.equal(((await over.json()) as { code: string }).code, "storage_quota");
  });

  await t.test("reactions per person per message are bounded, and retries of a saved one still succeed", async () => {
    const fan = await account("Fan");
    const room = randomUUID(),
      mid = randomUUID();
    await request("/api/conversations", "POST", { id: room }, fan.token);
    await request(`/api/conversations/${room}/messages`, "POST", { id: mid, text: "React to me" }, fan.token);
    const first = randomUUID();
    const react = (id: string) => request(`/api/messages/${mid}/reaction`, "PUT", { id, emoji: "👏" }, fan.token);
    assert.equal((await react(first)).status, 200);
    for (let i = 1; i < 200; i++) assert.equal((await react(randomUUID())).status, 200);
    assert.equal((await react(randomUUID())).status, 429);
    assert.equal((await react(first)).status, 200, "an idempotent retry isn't a new reaction");
  });

  const reported = randomUUID();
  await t.test("reports require access to the message, and bodies are bounded", async () => {
    await request(`/api/conversations/${cid}/messages`, "POST", { id: reported, text: "Something awful" }, alice.token);
    assert.equal((await request("/api/reports", "POST", { message_id: reported, reason: "spam" }, eve.token)).status, 404);
    assert.equal((await request("/api/reports", "POST", { message_id: reported, reason: "Abusive" }, bob.token)).status, 200);
    const huge = await request("/api/reports", "POST", { message_id: reported, reason: "x".repeat(70000) }, bob.token);
    assert.equal(huge.status, 413);
  });

  await t.test("admin API is invisible to non-admins and moderates reports", async () => {
    assert.equal((await request("/admin/me", "GET", undefined, mod.token)).status, 404);
    d1.prepare("INSERT INTO admins VALUES(?,?)").run(mod.id, Date.now());
    // The admin console signs in server-side with its own origin; unknown origins are refused.
    assert.equal((await signIn(mod.email, mod.password, "http://localhost:3000")).status, 200);
    assert.equal((await signIn(mod.email, mod.password, "https://attacker.example")).status, 403);
    assert.equal((await request("/admin/me", "GET", undefined, mod.token)).status, 200);

    const media = await upload(alice.token, cid, fixture("tiny-video.mp4"), "video");
    await media.done;
    const asset = d1.prepare("SELECT id FROM assets WHERE message_id=?").get(media.id) as { id: string };
    const range = await request(`/admin/media/${asset.id}`, "GET", undefined, mod.token, { Range: "bytes=0-99" });
    assert.equal(range.status, 206);
    assert.equal((await range.arrayBuffer()).byteLength, 100);
    assert.equal((await request(`/admin/media/${asset.id}`, "GET", undefined, bob.token)).status, 404);

    const queue = (await (await request("/admin/reports?status=open", "GET", undefined, mod.token)).json()) as {
      reports: { id: string; message: { id: string }; author: { id: string }; reporter: { id: string } }[];
    };
    const report = queue.reports.find((r) => r.message.id === reported)!;
    assert.equal(report.author.id, alice.id);
    assert.equal(report.reporter.id, bob.id);
    assert.equal(
      (await request(`/admin/reports/${report.id}/resolve`, "POST", { action: "remove_and_suspend" }, mod.token)).status,
      400,
      "suspension needs a reason",
    );

    const live = socket(alice.token, cid);
    await live.opened;
    const resolved = await request(
      `/admin/reports/${report.id}/resolve`,
      "POST",
      { action: "remove_and_suspend", note: "Harassment" },
      mod.token,
    );
    assert.equal(resolved.status, 200, await resolved.clone().text());
    assert.equal(await live.closed, 1008, "the suspended person's live socket is closed");

    const me = await request("/api/me", "GET", undefined, alice.token);
    assert.equal(me.status, 401, "suspension revokes existing sessions");
    const again = await signIn(alice.email, alice.password);
    assert.equal(again.status, 403);
    assert.equal(((await again.json()) as { code: string }).code, "ACCOUNT_SUSPENDED");
    const history = (await (await request(`/api/conversations/${cid}/messages`, "GET", undefined, bob.token)).json()) as {
      id: string;
    }[];
    assert.ok(!history.some((m) => m.id === reported), "the reported message is removed");
    const done = (await (await request("/admin/reports?status=resolved", "GET", undefined, mod.token)).json()) as {
      reports: { id: string; resolution: string }[];
    };
    assert.equal(done.reports.find((r) => r.id === report.id)?.resolution, "removed_and_suspended");
    const audit = (await (await request("/admin/audit", "GET", undefined, mod.token)).json()) as {
      entries: { action: string; target_id: string }[];
    };
    assert.ok(audit.entries.some((e) => e.action === "suspend" && e.target_id === alice.id));

    assert.equal((await request(`/admin/users/${alice.id}/unsuspend`, "POST", {}, mod.token)).status, 200);
    const restored = await signIn(alice.email, alice.password);
    assert.equal(restored.status, 200);
    tokens.push(restored.headers.get("set-auth-token")!);
    const users = (await (await request(`/admin/users?q=${encodeURIComponent(alice.email)}`, "GET", undefined, mod.token)).json()) as {
      users: { id: string; suspended_at: number | null }[];
    };
    assert.deepEqual(users.users.map((u) => [u.id, u.suspended_at]), [[alice.id, null]]);
  });

  await t.test("password reset works once, revokes sessions and doesn't reveal accounts", async () => {
    const person = await account("Forgetful");
    const emails = () =>
      d1.prepare("SELECT text FROM dev_emails WHERE recipient=? ORDER BY id").all(person.email) as { text: string }[];
    const ask = (email: string) =>
      request("/auth/request-password-reset", "POST", { email, redirectTo: `${base}/reset-password` });
    assert.equal((await ask(`${randomUUID()}@example.test`)).status, 200, "unknown email looks the same");
    assert.equal((await ask(person.email)).status, 200);
    await eventually(() => emails().length === 2, "the reset email", 5000);
    const link = emails()
      .map((e) => e.text.match(/\/reset-password\?token=([^\s]+)/)?.[1])
      .find(Boolean)!;
    const token = decodeURIComponent(link);
    assert.equal((await request(`/reset-password?token=${link}`)).status, 200);
    const submit = (password: string, confirm = password) =>
      request("/reset-password", "POST", new URLSearchParams({ token, password, confirm }).toString(), undefined, {
        "Content-Type": "application/x-www-form-urlencoded",
      });
    assert.equal((await submit("new-password-1", "different-1")).status, 400);
    assert.equal((await submit("new-password-1")).status, 200);
    assert.equal((await submit("another-password-1")).status, 400, "links work once");
    assert.equal((await request("/api/me", "GET", undefined, person.token)).status, 401);
    assert.equal((await signIn(person.email, person.password)).status, 401);
    const fresh = await signIn(person.email, "new-password-1");
    assert.equal(fresh.status, 200);
    tokens.push(fresh.headers.get("set-auth-token")!);
  });

  await t.test("deleting an account closes live sockets in conversations it didn't own", async () => {
    const owner = await account("Owner"),
      member = await account("Member");
    const room = randomUUID();
    await request("/api/conversations", "POST", { id: room }, owner.token);
    const link = (await (await request(`/api/conversations/${room}/invite`, "POST", {}, owner.token)).json()) as {
      token: string;
    };
    await request("/api/join", "POST", { token: link.token }, member.token);
    const live = socket(member.token, room);
    await live.opened;
    assert.equal((await request("/api/account/delete", "POST", { confirmation: "DELETE" }, member.token)).status, 200);
    assert.equal(await live.closed, 1008);
  });

  await t.test("diagnostics are bounded and visible to admins only", async () => {
    const report = {
      platform: "ios",
      app_version: "0.1.2",
      build: "3",
      os_version: "iOS 26.0",
      kind: "crash",
      payload: { signal: "SIGABRT", frames: ["main"] },
    };
    assert.equal((await request("/api/diagnostics", "POST", report, bob.token)).status, 202);
    const oversized = { ...report, payload: { blob: "x".repeat(300 * 1024) } };
    assert.equal((await request("/api/diagnostics", "POST", oversized, bob.token)).status, 413);
    const list = (await (await request("/admin/diagnostics", "GET", undefined, mod.token)).json()) as {
      diagnostics: { id: string; user_id: string }[];
    };
    const mine = list.diagnostics.find((d) => d.user_id === bob.id)!;
    const detail = (await (await request(`/admin/diagnostics/${mine.id}`, "GET", undefined, mod.token)).json()) as {
      payload: unknown;
    };
    assert.deepEqual(detail.payload, report.payload);
    assert.equal((await request("/admin/diagnostics", "GET", undefined, bob.token)).status, 404);
  });

  await t.test("failed jobs can be redriven from the admin API", async () => {
    const id = `delete:${randomUUID()}`;
    d1.prepare("INSERT INTO failed_jobs VALUES(?,?,?,?,?)").run(id, "delete", JSON.stringify({ messageId: randomUUID() }), 5, Date.now());
    const jobs = (await (await request("/admin/jobs/failed", "GET", undefined, mod.token)).json()) as { jobs: { id: string }[] };
    assert.ok(jobs.jobs.some((j) => j.id === id));
    assert.equal((await request(`/admin/jobs/${encodeURIComponent(id)}/retry`, "POST", {}, mod.token)).status, 200);
    assert.ok(!d1.prepare("SELECT 1 FROM failed_jobs WHERE id=?").get(id));
  });
});
