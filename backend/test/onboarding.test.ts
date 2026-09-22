import { localFetch as fetch } from "./http";
import { test } from "node:test";
import assert from "node:assert/strict";
import { randomUUID, createHash } from "node:crypto";
import { DatabaseSync } from "node:sqlite";
const base = process.env.TEST_API_URL ?? "http://localhost:8787";

test("open signup, six-character passwords, nameless contacts and retry-safe invitation links", async (t) => {
  const accounts: string[] = [];
  const request = (path: string, body?: unknown, token?: string) =>
    fetch(base + path, {
      method: body === undefined ? "GET" : "POST",
      headers: {
        Origin: base,
        "Content-Type": "application/json",
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
      },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
  t.after(async () => {
    for (const token of accounts)
      await request("/api/account/delete", { confirmation: "DELETE" }, token);
  });
  const signup = async (name: string) => {
    const email = `${randomUUID()}@example.test`;
    const response = await request("/auth/sign-up/email", {
      name,
      email,
      password: "abcdef",
    });
    assert.equal(response.status, 200, await response.clone().text());
    const token = response.headers.get("set-auth-token")!;
    accounts.push(token);
    const { user } = (await response.json()) as { user: { id: string } };
    const login = await request("/auth/sign-in/email", {
      email,
      password: "abcdef",
    });
    assert.equal(login.status, 200);
    return { token, id: user.id };
  };
  assert.equal(
    (
      await request("/auth/sign-up/email", {
        name: "Short",
        email: `${randomUUID()}@example.test`,
        password: "abcde",
      })
    ).status,
    400,
  );
  const alice = await signup("Alice"),
    bob = await signup("Bob"),
    outsider = await signup("Outsider");
  const cid = randomUUID(),
    invite = randomUUID();
  const create = { id: cid, invite_id: invite };
  for (let i = 0; i < 2; i++) {
    const response = await request("/api/conversations", create, alice.token);
    assert.equal(response.status, 201, await response.clone().text());
    assert.deepEqual(await response.json(), {
      id: cid,
      url: `${base}/join/${invite}`,
    });
  }
  const landing = await request(`/join/${invite}`);
  assert.equal(landing.status, 200);
  assert.equal(landing.headers.get("referrer-policy"), "no-referrer");
  assert.match(await landing.text(), new RegExp(`genoa://join/${invite}`));
  assert.equal(
    (await request("/api/join", { token: invite }, bob.token)).status,
    200,
  );
  const rows = (await (
    await request("/api/conversations", undefined, alice.token)
  ).json()) as { id: string; title: string }[];
  assert.equal(rows.filter((r) => r.id === cid).length, 1);
  assert.equal(rows.find((r) => r.id === cid)?.title, "Bob");
  const group = randomUUID();
  assert.equal(
    (
      await request(
        "/api/conversations",
        { id: group, member_ids: [bob.id], invite_id: randomUUID() },
        alice.token,
      )
    ).status,
    201,
  );
  assert.equal(
    (
      await request(
        "/api/conversations",
        { id: randomUUID(), member_ids: [outsider.id] },
        alice.token,
      )
    ).status,
    403,
  );
  assert.equal(
    (
      await request(
        "/api/conversations",
        { id: randomUUID(), invite_id: invite },
        alice.token,
      )
    ).status,
    409,
  );
  assert.equal(
    (await request("/api/conversations", create, outsider.token)).status,
    409,
  );
  const raced = { id: randomUUID(), invite_id: randomUUID() };
  const attempts = await Promise.all(
    [alice, outsider].map((account) =>
      request("/api/conversations", raced, account.token),
    ),
  );
  assert.deepEqual(attempts.map((result) => result.status).sort(), [201, 409]);
  assert.ok(attempts[0]);
  const winner = attempts[0].status === 201 ? alice : outsider;
  const loser = winner === alice ? outsider : alice;
  assert.equal(
    (await request(`/api/conversations/${raced.id}`, undefined, loser.token))
      .status,
    404,
  );
  const detail = (await (
    await request(`/api/conversations/${raced.id}`, undefined, winner.token)
  ).json()) as { members: { id: string }[] };
  assert.deepEqual(
    detail.members.map((member) => member.id),
    [winner.id],
  );
});

test(
  "OAuth handoff requires browser binding and native proof, rejects replay and expired sessions",
  { skip: !process.env.TEST_D1_FILE },
  async (t) => {
    const path = process.env.TEST_D1_FILE!;
    assert.ok(path.startsWith("/tmp/genoa-ios-server."));
    assert.ok(["localhost", "127.0.0.1"].includes(new URL(base).hostname));
    const db = new DatabaseSync(path);
    db.exec("PRAGMA busy_timeout = 10000");
    const account = await fetch(base + "/auth/sign-up/email", {
      method: "POST",
      headers: { Origin: base, "Content-Type": "application/json" },
      body: JSON.stringify({
        name: "OAuth fixture",
        email: `${randomUUID()}@example.test`,
        password: "abcdef",
      }),
    });
    assert.equal(account.status, 200, await account.clone().text());
    const token = account.headers.get("set-auth-token")!;
    t.after(async () => {
      db.close();
      await fetch(base + "/api/account/delete", {
        method: "POST",
        headers: {
          Origin: base,
          "Content-Type": "application/json",
          Authorization: `Bearer ${token}`,
        },
        body: JSON.stringify({ confirmation: "DELETE" }),
      });
    });
    const verifier = randomUUID() + randomUUID();
    const challenge = createHash("sha256").update(verifier).digest("base64url");
    const code = randomUUID(),
      browser = randomUUID();
    db.prepare(
      "INSERT INTO mobile_logins(id,provider,challenge,browser,expires_at) VALUES(?,'google',?,?,?)",
    ).run(code, challenge, browser, Date.now() + 60000);
    const complete = (nonce: string) =>
      fetch(base + `/auth/mobile/complete?id=${code}`, {
        redirect: "manual",
        headers: {
          Origin: base,
          Cookie: `better-auth.session_token=${token}; genoa_login=${nonce}`,
        },
      });
    assert.equal((await complete(randomUUID())).status, 410);
    const completed = await complete(browser);
    assert.equal(completed.status, 302, await completed.clone().text());
    assert.equal(
      completed.headers.get("location"),
      `genoa://auth?code=${code}`,
    );
    const exchange = (proof: string) =>
      fetch(base + "/auth/mobile/exchange", {
        method: "POST",
        headers: { Origin: base, "Content-Type": "application/json" },
        body: JSON.stringify({ code, verifier: proof }),
      });
    assert.equal((await exchange(randomUUID() + randomUUID())).status, 401);
    const redeemed = await exchange(verifier);
    assert.equal(redeemed.status, 200, await redeemed.clone().text());
    assert.equal(redeemed.headers.get("set-auth-token"), token);
    assert.equal((await exchange(verifier)).status, 401);
    db.prepare(
      "INSERT INTO mobile_logins(id,provider,challenge,session_cookie,expires_at) VALUES(?,'google',?,?,?)",
    ).run(code, challenge, token, Date.now() - 1);
    assert.equal((await exchange(verifier)).status, 401);
    const providers = await fetch(base + "/auth/mobile/providers");
    assert.deepEqual(await providers.json(), { providers: [] });
    const disabled = await fetch(base + "/auth/mobile/start", {
      method: "POST",
      headers: { Origin: base, "Content-Type": "application/json" },
      body: JSON.stringify({ provider: "google", challenge }),
    });
    assert.equal(disabled.status, 503);
  },
);
