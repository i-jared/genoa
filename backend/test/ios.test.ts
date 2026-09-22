import { localFetch as fetch } from "./http";
import { test } from "node:test";
import assert from "node:assert/strict";
import { randomUUID, generateKeyPairSync } from "node:crypto";
import { sendApplePush } from "../src/apple-push";
import type { Env } from "../src/types";

const base = process.env.TEST_API_URL ?? "http://localhost:8787";
test("iOS fragmented HLS preserves authorization, contiguous replay, retries, and deletion", async (t) => {
  const accounts: string[] = [];
  const request = (
    path: string,
    token: string,
    method = "GET",
    body?: unknown,
  ) =>
    fetch(base + path, {
      method,
      headers: {
        Origin: base,
        Authorization: `Bearer ${token}`,
        "Content-Type": "application/json",
      },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
  const make = async () => {
    const r = await fetch(base + "/auth/sign-up/email", {
      method: "POST",
      headers: {
        Origin: base,
        "X-Forwarded-For": `192.0.2.${180 + accounts.length}`,
        "Content-Type": "application/json",
        "X-Invite-Code": process.env.TEST_INVITE_CODE ?? "local-genoa-beta",
      },
      body: JSON.stringify({
        name: "iOS tester",
        email: `${randomUUID()}@example.test`,
        password: "local-test-password-123",
      }),
    });
    assert.equal(r.status, 200, await r.clone().text());
    const token = r.headers.get("set-auth-token")!;
    accounts.push(token);
    return token;
  };
  t.after(async () => {
    for (const token of accounts)
      await request("/api/account/delete", token, "POST", {
        confirmation: "DELETE",
      });
  });
  const owner = await make(),
    outsider = await make(),
    cid = randomUUID(),
    mid = randomUUID();
  assert.equal(
    (
      await request("/api/conversations", owner, "POST", {
        id: cid,
        title: "iOS HLS",
      })
    ).status,
    201,
  );
  assert.equal(
    (
      await request(`/api/conversations/${cid}/live`, owner, "POST", {
        id: mid,
      })
    ).status,
    200,
  );
  const put = (path: string, token: string) =>
    fetch(base + path, {
      method: "PUT",
      headers: {
        Authorization: `Bearer ${token}`,
        "Content-Type": "application/octet-stream",
      },
      body: new Uint8Array([0, 0, 0, 8, 102, 116, 121, 112]),
    });
  assert.equal((await put(`/api/live/${mid}/init.mp4`, outsider)).status, 404);
  assert.equal((await put(`/api/live/${mid}/init.mp4`, owner)).status, 200);
  assert.equal((await put(`/api/live/${mid}/init.mp4`, owner)).status, 200);
  assert.equal(
    (await request(`/api/live/${mid}/init.mp4`, outsider)).status,
    404,
  );
  const cookie = await fetch(base + `/api/live/${mid}/init.mp4`, {
    headers: { Cookie: `better-auth.session_token=${owner}` },
  });
  assert.equal(
    cookie.status,
    200,
    "AVFoundation signed session cookie must authorize media",
  );
  assert.equal(
    (await put(`/api/live/${mid}/segments/0?duration=2`, owner)).status,
    409,
  );
  assert.equal(
    (await put(`/api/live/${mid}/segments/1?duration=2&format=fmp4`, owner))
      .status,
    200,
  );
  let playlist = await (
    await request(`/api/live/${mid}/playlist.m3u8`, owner)
  ).text();
  assert.match(playlist, /#EXT-X-MAP:URI="init.mp4"/);
  assert.doesNotMatch(playlist, /segments\/1/);
  assert.equal(
    (await put(`/api/live/${mid}/segments/0?duration=2&format=fmp4`, owner))
      .status,
    200,
  );
  assert.equal(
    (await put(`/api/live/${mid}/segments/0?duration=2&format=fmp4`, owner))
      .status,
    200,
  );
  assert.equal(
    (await request(`/api/live/${mid}/segments/0.m4s`, owner)).status,
    200,
  );
  assert.equal(
    (await request(`/api/live/${mid}/segments/0.ts`, owner)).status,
    404,
  );
  assert.equal(
    (await request(`/api/live/${mid}/segments/0.m4s`, outsider)).status,
    404,
  );
  await request(`/api/live/${mid}/end`, owner, "POST", {});
  playlist = await (
    await request(`/api/live/${mid}/playlist.m3u8`, owner)
  ).text();
  assert.match(playlist, /segments\/0.m4s/);
  assert.match(playlist, /segments\/1.m4s/);
  assert.match(playlist, /#EXT-X-ENDLIST/);
  await request(`/api/messages/${mid}`, owner, "DELETE");
  assert.equal((await request(`/api/live/${mid}/init.mp4`, owner)).status, 404);
  assert.equal((await put(`/api/live/${mid}/init.mp4`, owner)).status, 404);
  assert.equal(
    (await put(`/api/live/${mid}/segments/2?duration=2&format=fmp4`, owner))
      .status,
    404,
  );
  assert.equal(
    (
      await request("/api/devices", owner, "PUT", {
        token: "a".repeat(64),
        platform: "ios-sandbox",
      })
    ).status,
    200,
  );
  assert.equal(
    (
      await request("/api/devices/current", outsider, "DELETE", {
        token: "a".repeat(64),
      })
    ).status,
    200,
  );
  assert.equal(
    (
      await request("/api/devices/current", owner, "DELETE", {
        token: "a".repeat(64),
      })
    ).status,
    200,
  );
});
test("APNs uses environment, exact navigation, distinct reminder identities, and removes rejected tokens", async (t) => {
  const { privateKey } = generateKeyPairSync("ec", {
    namedCurve: "prime256v1",
  });
  const requests: { url: string; init: RequestInit }[] = [];
  const deleted: unknown[][] = [];
  const env = {
    APNS_CREDENTIALS: JSON.stringify({
      team_id: "ABCDEFGHIJ",
      key_id: "KLMNOPQRST",
      bundle_id: "com.talkgenoa.ios",
      private_key: privateKey.export({ type: "pkcs8", format: "pem" }),
    }),
    DB: {
      prepare: () => ({
        bind: (...args: unknown[]) => ({
          run: async () => {
            deleted.push(args);
          },
        }),
      }),
    },
  } as unknown as Env;
  t.mock.method(globalThis, "fetch", async (url: string, init: RequestInit) => {
    requests.push({ url, init });
    return requests.length === 2
      ? Response.json({ reason: "Unregistered" }, { status: 410 })
      : new Response(null, { status: 200 });
  });
  for (const platform of ["ios-sandbox", "ios"])
    await sendApplePush(
      env,
      { token: "a".repeat(64), platform },
      {
        title: "Genoa",
        body: "A new message is waiting",
        conversation_id: "conversation",
        message_id: "message",
        event_id: platform,
      },
    );
  assert.match(requests[0]!.url, /api.sandbox.push.apple.com/);
  assert.match(requests[1]!.url, /api.push.apple.com/);
  const payload = JSON.parse(String(requests[0]!.init.body));
  assert.equal(payload.message_id, "message");
  assert.equal(payload.conversation_id, "conversation");
  assert.equal(
    (requests[0]!.init.headers as Record<string, string>)["apns-push-type"],
    "alert",
  );
  assert.deepEqual(deleted, [["a".repeat(64), "ios"]]);
});
