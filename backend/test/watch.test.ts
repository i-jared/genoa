import { test } from "node:test";
import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
const base = process.env.TEST_API_URL ?? "http://localhost:8787";
test("unseen history across pages and named idempotent reactions", async (t) => {
  const tokens: string[] = [];
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
  const json = async (
    path: string,
    token: string,
    method = "GET",
    body?: unknown,
  ) => {
    const r = await request(path, token, method, body);
    assert.ok(r.ok, `${r.status}: ${await r.clone().text()}`);
    return r.json() as Promise<any>;
  };
  t.after(async () => {
    for (const token of tokens)
      await request("/api/account/delete", token, "POST", {
        confirmation: "DELETE",
      });
  });
  const account = async (name: string) => {
    const r = await fetch(base + "/auth/sign-up/email", {
      method: "POST",
      headers: {
        Origin: base,
        "X-Forwarded-For": `192.0.2.${100 + tokens.length}`,
        "Content-Type": "application/json",
        "X-Invite-Code": process.env.TEST_INVITE_CODE ?? "local-genoa-beta",
      },
      body: JSON.stringify({
        name,
        email: `${randomUUID()}@example.test`,
        password: "local-test-password-123",
      }),
    });
    assert.equal(r.status, 200, await r.clone().text());
    const token = r.headers.get("set-auth-token")!;
    assert.ok(token);
    tokens.push(token);
    return { token, id: ((await r.json()) as any).user.id as string };
  };
  const viewer = await account("Viewer"),
    sender = await account("Rosa"),
    outsider = await account("Outsider");
  const { id: cid } = await json("/api/conversations", sender.token, "POST", {
    id: randomUUID(),
    title: "History regression",
  });
  const invite = await json(
    `/api/conversations/${cid}/invite`,
    sender.token,
    "POST",
  );
  await json("/api/join", viewer.token, "POST", { token: invite.token });
  const ids: string[] = [];
  for (let i = 0; i < 65; i++) {
    const id = randomUUID();
    ids.push(id);
    await json(`/api/conversations/${cid}/messages`, sender.token, "POST", {
      id,
      text: `Message ${i}`,
    });
  }
  for (const id of ids.slice(0, 4))
    await json(`/api/messages/${id}/progress`, viewer.token, "PUT", {
      position_ms: 0,
      watched: true,
    });
  let page = await json(`/api/conversations/${cid}/watch`, viewer.token);
  assert.equal(page.focus_id, ids[4], "Unseen outside the latest 50 must win");
  assert.equal(page.has_older, false);
  assert.equal(page.has_newer, true);
  const exact = await json(`/api/conversations/${cid}/watch?message=${ids[60]}`, viewer.token);
  assert.equal(exact.focus_id, ids[60], "A notification target must win over first unseen");
  assert.ok(exact.messages.some((m: any) => m.id === ids[60]));
  assert.equal((await request(`/api/conversations/${cid}/watch?message=${ids[60]}`, outsider.token)).status, 404);
  assert.equal((await request(`/api/conversations/${cid}/watch?message=oops`, viewer.token)).status, 400);
  assert.equal((await request(`/api/conversations/${cid}/watch?message=${ids[60]}&around=1`, viewer.token)).status, 400);
  const loaded = [...page.messages];
  while (page.has_newer) {
    page = await json(
      `/api/conversations/${cid}/watch?after=${loaded.at(-1).seq}`,
      viewer.token,
    );
    loaded.push(...page.messages);
  }
  assert.deepEqual(
    loaded.map((m: any) => m.id),
    ids,
  );
  for (const id of ids.slice(4))
    await json(`/api/messages/${id}/progress`, viewer.token, "PUT", {
      position_ms: 0,
      watched: true,
    });
  page = await json(`/api/conversations/${cid}/watch`, viewer.token);
  assert.equal(page.focus_id, ids.at(-1));
  await json(`/api/messages/${ids[0]}/progress`, viewer.token, "PUT", {position_ms: 123, watched: false});
  const retried = await json(`/api/conversations/${cid}/watch?message=${ids[0]}`, viewer.token);
  assert.equal(retried.messages.find((m: any) => m.id === ids[0]).watched, 1, "A late partial update cannot undo completion");
  assert.equal((await json("/api/conversations", viewer.token))[0].unread, 0);
  assert.equal(page.has_newer, false);
  assert.equal(page.has_older, true);
  const older = await json(
    `/api/conversations/${cid}/watch?before=${page.messages[0].seq}`,
    viewer.token,
  );
  assert.deepEqual(
    [...older.messages, ...page.messages].map((m: any) => m.id),
    ids,
  );
  assert.equal(older.has_older, false);
  assert.equal(
    (await request(`/api/conversations/${cid}/watch`, outsider.token)).status,
    404,
  );
  assert.equal(
    (
      await request(
        `/api/conversations/${cid}/watch?after=1&before=2`,
        viewer.token,
      )
    ).status,
    400,
  );
  assert.equal(
    (await request(`/api/conversations/${cid}/watch?around=oops`, viewer.token))
      .status,
    400,
  );
  const reactionId = randomUUID(),
    mid = ids[4];
  for (let retry = 0; retry < 2; retry++)
    await json(`/api/messages/${mid}/reaction`, sender.token, "PUT", {
      id: reactionId,
      emoji: "❤️",
      position_ms: 1234,
    });
  assert.equal(
    (
      await request(`/api/messages/${mid}/reaction`, viewer.token, "PUT", {
        id: reactionId,
        emoji: "🔥",
        position_ms: 1234,
      })
    ).status,
    409,
  );
  page = await json(
    `/api/conversations/${cid}/watch?around=${loaded[4].seq}`,
    viewer.token,
  );
  const reactions = page.messages.find((m: any) => m.id === mid).reactions;
  assert.equal(reactions.length, 1);
  assert.equal(reactions[0].id, reactionId);
  assert.equal(reactions[0].user_name, "Rosa");
  assert.equal(reactions[0].position_ms, 1234);
  await json(`/api/messages/${mid}`, sender.token, "DELETE");
  assert.equal((await request(`/api/conversations/${cid}/watch?message=${mid}`, viewer.token)).status, 404);
  page = await json(
    `/api/conversations/${cid}/watch?around=${loaded[4].seq}`,
    viewer.token,
  );
  assert.ok(!page.messages.some((m: any) => m.id === mid));
  assert.ok(page.start_seq <= loaded[4].seq && page.end_seq >= loaded[4].seq);
  await json("/api/blocks", viewer.token, "POST", { user_id: sender.id });
  assert.equal((await request(`/api/conversations/${cid}/watch?message=${ids[60]}`, viewer.token)).status, 404);
  page = await json(`/api/conversations/${cid}/watch`, viewer.token);
  assert.equal(page.messages.length, 0);
  assert.equal(page.focus_id, null);
});
