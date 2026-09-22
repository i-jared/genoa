import { test } from "node:test";
import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
const base = process.env.TEST_API_URL ?? "http://localhost:8787";
test("private messaging, retries, media range requests, and membership revocation", async (t) => {
  const createdAccounts: string[] = [];
  t.after(async () => {
    for (const token of createdAccounts)
      await request(
        "/api/account/delete",
        "POST",
        { confirmation: "DELETE" },
        token,
      );
  });
  async function request(
    path: string,
    method = "GET",
    value?: unknown,
    token?: string,
    headers: Record<string, string> = {},
  ) {
    return fetch(base + path, {
      method,
      headers: {
        Origin: base,
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
        ...(value ? { "Content-Type": "application/json" } : {}),
        ...headers,
      },
      body: value ? JSON.stringify(value) : undefined,
    });
  }
  async function account(name: string) {
    const response = await request(
      "/auth/sign-up/email",
      "POST",
      {
        name,
        email: `${randomUUID()}@example.test`,
        password: "local-test-password-123",
      },
      undefined,
      { "X-Invite-Code": process.env.TEST_INVITE_CODE ?? "local-genoa-beta" },
    );
    assert.equal(response.status, 200, await response.clone().text());
    const data = (await response.json()) as { user: { id: string } };
    const token = response.headers.get("set-auth-token");
    assert.ok(token);
    createdAccounts.push(token);
    return { token, id: data.user.id };
  }
  const alice = await account("Alice"),
    bob = await account("Bob"),
    eve = await account("Eve");
  assert.equal((await request("/api/conversations")).status, 401);
  const cid = randomUUID();
  assert.equal(
    (
      await request(
        "/api/conversations",
        "POST",
        { id: cid, title: "Family" },
        alice.token,
      )
    ).status,
    201,
  );
  assert.equal(
    (await request(`/api/conversations/${cid}`, "GET", undefined, eve.token))
      .status,
    404,
  );
  const invitation = (await (
    await request(`/api/conversations/${cid}/invite`, "POST", {}, alice.token)
  ).json()) as { token: string; url: string };
  assert.equal(invitation.url, `genoa://join/${invitation.token}`);
  assert.equal(
    (await request("/api/join", "POST", { token: invitation.token }, bob.token))
      .status,
    200,
  );
  const mid = randomUUID(),
    payload = { id: mid, text: "A message worth keeping" };
  assert.equal(
    (
      await request(
        `/api/conversations/${cid}/messages`,
        "POST",
        payload,
        alice.token,
      )
    ).status,
    201,
  );
  assert.equal(
    (
      await request(
        `/api/conversations/${cid}/messages`,
        "POST",
        payload,
        alice.token,
      )
    ).status,
    201,
  );
  const history = (await (
    await request(
      `/api/conversations/${cid}/messages`,
      "GET",
      undefined,
      bob.token,
    )
  ).json()) as { id: string }[];
  assert.equal(
    history.filter((m) => m.id === mid).length,
    1,
    "Retry must not duplicate a message",
  );
  assert.equal(
    (await request(`/api/messages/${mid}`, "DELETE", {}, bob.token)).status,
    403,
  );
  assert.equal(
    (
      await request(
        `/api/messages/${mid}/bookmark`,
        "PUT",
        { enabled: true },
        bob.token,
      )
    ).status,
    200,
  );
  const saved = (await (
    await request(
      `/api/conversations/${cid}/messages?bookmarked=true`,
      "GET",
      undefined,
      bob.token,
    )
  ).json()) as { id: string }[];
  assert.deepEqual(
    saved.map((message) => message.id),
    [mid],
  );
  const privateSaved = await request(
    `/api/conversations/${cid}/messages?bookmarked=true`,
    "GET",
    undefined,
    alice.token,
  );
  assert.deepEqual(
    await privateSaved.json(),
    [],
    "Bookmarks belong only to their owner",
  );
  assert.equal(
    (
      await request(
        `/api/messages/${mid}/reaction`,
        "PUT",
        { emoji: "❤️", position_ms: 1200 },
        bob.token,
      )
    ).status,
    200,
  );
  assert.equal(
    (
      await request(
        `/api/messages/${mid}/reaction`,
        "PUT",
        { emoji: "😂", position_ms: 2400 },
        bob.token,
      )
    ).status,
    200,
  );
  const reactedMessages = (await (
    await request(`/api/conversations/${cid}/messages`, "GET", undefined, bob.token)
  ).json()) as { id: string; reactions: { emoji: string; position_ms: number }[] }[];
  const reacted = reactedMessages.find((row) => row.id === mid)!;
  assert.deepEqual(
    reacted.reactions
      .map((row) => [row.emoji, row.position_ms])
      .sort((a, b) => Number(a[1]) - Number(b[1])),
    [
      ["❤️", 1200],
      ["😂", 2400],
    ],
  );
  assert.equal(
    (
      await request(
        `/api/conversations/${cid}/note`,
        "PUT",
        { text: "Private to Alice" },
        alice.token,
      )
    ).status,
    200,
  );
  const detail = (await (
    await request(`/api/conversations/${cid}`, "GET", undefined, bob.token)
  ).json()) as { note: string };
  assert.equal(detail.note, "");
  const conversationList = (await (
    await request("/api/conversations", "GET", undefined, bob.token)
  ).json()) as {
    id: string;
    unread: number;
    unwatched: number;
    preview_data: string;
  }[];
  const currentConversation = conversationList.find(
    (conversation) => conversation.id === cid,
  )!;
  assert.equal(currentConversation.unread, 1);
  assert.equal(
    currentConversation.unwatched,
    0,
    "Text messages must not count as unwatched media",
  );
  assert.equal(JSON.parse(currentConversation.preview_data).kind, "text");
  const people = (await (
    await request("/api/people", "GET", undefined, alice.token)
  ).json()) as { id: string; name: string }[];
  assert.ok(people.some((person) => person.id === bob.id));
  assert.ok(!people.some((person) => person.id === eve.id));
  const hike = randomUUID();
  assert.equal(
    (
      await request(
        "/api/conversations",
        "POST",
        { id: hike, title: "Hike", member_ids: [bob.id] },
        alice.token,
      )
    ).status,
    201,
  );
  assert.equal(
    (await request(`/api/conversations/${hike}`, "GET", undefined, bob.token))
      .status,
    200,
  );
  assert.equal(
    (
      await request(
        "/api/conversations",
        "POST",
        { id: randomUUID(), title: "Secret", member_ids: [eve.id] },
        alice.token,
      )
    ).status,
    403,
    "Cannot add someone you have not already talked with",
  );
  const upload = randomUUID();
  const bytes = Uint8Array.from([255, 216, 255, 224, 1, 2, 3, 4, 5, 6, 7, 8]);
  const setup = {
    id: upload,
    conversation_id: cid,
    kind: "photo",
    mime: "image/jpeg",
    bytes: bytes.length,
    duration_ms: 0,
  };
  const started = await request("/api/uploads", "POST", setup, alice.token);
  assert.equal(started.status, 201, await started.clone().text());
  assert.equal(
    (await request(`/api/uploads/${upload}/complete`, "POST", {}, alice.token))
      .status,
    409,
  );
  const part = await fetch(`${base}/api/uploads/${upload}/parts/1`, {
    method: "PUT",
    headers: {
      Authorization: `Bearer ${alice.token}`,
      "Content-Type": "application/octet-stream",
    },
    body: bytes,
  });
  assert.equal(part.status, 200, await part.clone().text());
  assert.equal(
    (await request(`/api/uploads/${upload}/complete`, "POST", {}, alice.token))
      .status,
    200,
  );
  assert.equal(
    (await request(`/api/uploads/${upload}/complete`, "POST", {}, alice.token))
      .status,
    200,
  );
  assert.equal(
    (await request(`/api/media/${upload}`, "GET", undefined, eve.token)).status,
    404,
  );
  const media = await request(
    `/api/media/${upload}`,
    "GET",
    undefined,
    bob.token,
    { Range: "bytes=0-3" },
  );
  assert.equal(media.status, 206);
  assert.equal(media.headers.get("content-range"), "bytes 0-3/12");
  assert.equal((await media.arrayBuffer()).byteLength, 4);
  const resumeId = randomUUID();
  const largeBytes = new Uint8Array(8 * 1024 * 1024 + 37);
  largeBytes.set([255, 216, 255, 224]);
  const resumeSetup = {
    id: resumeId,
    conversation_id: cid,
    kind: "photo",
    mime: "image/jpeg",
    bytes: largeBytes.length,
    duration_ms: 0,
  };
  assert.equal(
    (await request("/api/uploads", "POST", resumeSetup, alice.token)).status,
    201,
  );
  const uploadPart = async (number: number, data: Uint8Array<ArrayBuffer>) =>
    fetch(`${base}/api/uploads/${resumeId}/parts/${number}`, {
      method: "PUT",
      headers: { Authorization: `Bearer ${alice.token}` },
      body: data,
    });
  assert.equal(
    (await uploadPart(1, largeBytes.slice(0, 8 * 1024 * 1024))).status,
    200,
  );
  // A restarted client recovers server-confirmed parts instead of resending the entire recording.
  assert.equal(
    (await request("/api/uploads", "POST", resumeSetup, alice.token)).status,
    200,
  );
  const recovered = (await (
    await request(`/api/uploads/${resumeId}`, "GET", undefined, alice.token)
  ).json()) as { parts: { number: number }[] };
  assert.deepEqual(
    recovered.parts.map((p) => p.number),
    [1],
  );
  assert.equal(
    (
      await request(
        `/api/uploads/${resumeId}/complete`,
        "POST",
        {},
        alice.token,
      )
    ).status,
    409,
  );
  assert.equal(
    (await uploadPart(2, largeBytes.slice(8 * 1024 * 1024))).status,
    200,
  );
  assert.equal(
    (
      await request(
        `/api/uploads/${resumeId}/complete`,
        "POST",
        {},
        alice.token,
      )
    ).status,
    200,
  );
  const resumedRange = await request(
    `/api/media/${resumeId}`,
    "GET",
    undefined,
    bob.token,
    { Range: "bytes=8388608-8388644" },
  );
  assert.equal(resumedRange.status, 206);
  assert.equal((await resumedRange.arrayBuffer()).byteLength, 37);
  const liveId = randomUUID();
  assert.equal(
    (
      await request(
        `/api/conversations/${cid}/live`,
        "POST",
        { id: liveId },
        alice.token,
      )
    ).status,
    200,
  );
  const segment = new Uint8Array(188);
  segment[0] = 0x47;
  const publish = (token: string) =>
    fetch(`${base}/api/live/${liveId}/segments/0?duration=2`, {
      method: "PUT",
      headers: { Authorization: `Bearer ${token}` },
      body: segment,
    });
  assert.equal((await publish(bob.token)).status, 403);
  assert.equal((await publish(alice.token)).status, 200);
  assert.equal((await publish(alice.token)).status, 200);
  assert.equal(
    (
      await request(
        `/api/live/${liveId}/playlist.m3u8`,
        "GET",
        undefined,
        eve.token,
      )
    ).status,
    404,
  );
  const playlist = await (
    await request(
      `/api/live/${liveId}/playlist.m3u8`,
      "GET",
      undefined,
      bob.token,
    )
  ).text();
  assert.equal((playlist.match(/#EXTINF/g) ?? []).length, 1);
  assert.equal(
    (await request(`/api/live/${liveId}/end`, "POST", {}, alice.token)).status,
    200,
  );
  assert.ok(
    (
      await (
        await request(
          `/api/live/${liveId}/playlist.m3u8`,
          "GET",
          undefined,
          bob.token,
        )
      ).text()
    ).includes("#EXT-X-ENDLIST"),
  );
  assert.equal(
    (await request(`/api/messages/${liveId}/ai`, "POST", {}, alice.token))
      .status,
    403,
    "AI requires author consent",
  );
  const reminderId = randomUUID();
  assert.equal(
    (
      await request(
        `/api/messages/${mid}/reminder`,
        "POST",
        { id: reminderId, due_at: Date.now() + 60000 },
        bob.token,
      )
    ).status,
    200,
  );
  const reminders = (await (
    await request("/api/reminders", "GET", undefined, bob.token)
  ).json()) as { id: string }[];
  assert.ok(reminders.some((r) => r.id === reminderId));
  assert.equal(
    (
      await request(
        `/api/reminders/${reminderId}`,
        "DELETE",
        undefined,
        bob.token,
      )
    ).status,
    200,
  );
  assert.equal(
    (
      await request(
        `/api/conversations/${cid}/owner`,
        "POST",
        { user_id: bob.id },
        eve.token,
      )
    ).status,
    404,
  );
  const revoke = await request(
    `/api/conversations/${cid}/members/${bob.id}`,
    "DELETE",
    {},
    alice.token,
  );
  assert.equal(revoke.status, 200);
  assert.equal(
    (await request(`/api/media/${upload}`, "GET", undefined, bob.token)).status,
    404,
    "Removed member cannot fetch media",
  );
  assert.equal(
    (await request(`/api/messages/${upload}`, "DELETE", {}, alice.token))
      .status,
    200,
  );
  assert.equal(
    (await request(`/api/media/${upload}`, "GET", undefined, alice.token))
      .status,
    404,
    "Deletion immediately revokes access",
  );
  assert.equal(
    (await request(`/api/uploads/${upload}/complete`, "POST", {}, alice.token))
      .status,
    404,
    "Late upload completion cannot restore deleted content",
  );
  const other = randomUUID();
  assert.equal(
    (
      await request(
        "/api/conversations",
        "POST",
        { id: other, title: "Forward destination" },
        alice.token,
      )
    ).status,
    201,
  );
  const forwarded = randomUUID();
  assert.equal(
    (
      await request(
        `/api/messages/${mid}/forward`,
        "POST",
        { id: forwarded, conversation_id: other },
        alice.token,
      )
    ).status,
    200,
  );
  assert.equal(
    (
      await request(
        `/api/messages/${forwarded}/bookmark`,
        "PUT",
        { enabled: true },
        bob.token,
      )
    ).status,
    404,
  );
  assert.equal(
    (
      await request(
        `/api/conversations/${cid}/owner`,
        "POST",
        { user_id: alice.id },
        alice.token,
      )
    ).status,
    200,
  );
  assert.equal(
    (await request("/api/account/export", "GET", undefined, alice.token))
      .status,
    200,
  );
  assert.equal(
    (
      await request(
        "/api/account/delete",
        "POST",
        { confirmation: "WRONG" },
        alice.token,
      )
    ).status,
    400,
  );
  assert.equal(
    (
      await request(
        "/api/account/delete",
        "POST",
        { confirmation: "DELETE" },
        alice.token,
      )
    ).status,
    200,
  );
  assert.equal(
    (await request("/api/me", "GET", undefined, alice.token)).status,
    401,
    "Deleting the account revokes its session",
  );
  for (const person of [bob, eve])
    assert.equal(
      (
        await request(
          "/api/account/delete",
          "POST",
          { confirmation: "DELETE" },
          person.token,
        )
      ).status,
      200,
    );
});
