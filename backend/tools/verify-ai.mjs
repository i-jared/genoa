import fs from "node:fs";
import { randomUUID } from "node:crypto";
const base = process.env.TEST_API_URL ?? "http://localhost:8787";
let token;
async function request(path, method = "GET", value) {
  const r = await fetch(base + path, {
    method,
    headers: {
      Origin: base,
      "Content-Type": "application/json",
      ...(token
        ? { Authorization: `Bearer ${token}` }
        : {
            "X-Invite-Code":
              process.env.TEST_INVITE_CODE ?? "local-genoa-beta",
          }),
    },
    body: value === undefined ? undefined : JSON.stringify(value),
  });
  if (!r.ok) throw Error(`${method} ${path}: ${r.status} ${await r.text()}`);
  token = r.headers.get("set-auth-token") ?? token;
  return r.json();
}
await request("/auth/sign-up/email", "POST", {
  name: "Synthetic AI test",
  email: `${randomUUID()}@example.test`,
  password: "synthetic-test-password-123",
});
await request("/api/me", "PATCH", { ai_consent: true, notifications: false });
const cid = randomUUID(),
  mid = randomUUID();
await request("/api/conversations", "POST", {
  id: cid,
  title: "Synthetic audio validation",
});
const audio = fs.readFileSync(
  new URL("../test/fixtures/synthetic-speech.m4a", import.meta.url),
);
await request("/api/uploads", "POST", {
  id: mid,
  conversation_id: cid,
  kind: "audio",
  mime: "audio/mp4",
  bytes: audio.length,
  duration_ms: 12000,
});
for (const path of [
  `/api/uploads/${mid}/parts/1`,
  `/api/messages/${mid}/audio/0?offset_ms=0`,
]) {
  const r = await fetch(base + path, {
    method: "PUT",
    headers: { Authorization: `Bearer ${token}` },
    body: audio,
  });
  if (!r.ok) throw Error(`Audio upload failed: ${r.status}`);
  if (path.includes("/parts/"))
    await request(`/api/uploads/${mid}/complete`, "POST", {});
}
await request(`/api/messages/${mid}/ai`, "POST", {});
for (let i = 0; i < 60; i++) {
  await new Promise((r) => setTimeout(r, 2000));
  const m = (await request(`/api/conversations/${cid}/messages`)).find(
    (m) => m.id === mid,
  );
  if (m.ai_status === "ready") {
    const captions = await request(`/api/messages/${mid}/captions`);
    const result = {
      status: m.ai_status,
      transcript: JSON.parse(m.transcript).text,
      summary: m.summary,
      captions,
    };

    if (
      !result.transcript.toLowerCase().includes("saturday") ||
      !result.summary ||
      !captions.length
    )
      throw Error("AI output incomplete");
    console.log(
      "Synthetic speech produced a transcript, summary, and " +
        captions.length +
        " timed captions.",
    );
    await request("/api/me", "PATCH", {
      ai_consent: false,
      notifications: false,
    });
    const revoked = (await request(`/api/conversations/${cid}/messages`)).find(
      (m) => m.id === mid,
    );
    if (
      revoked.transcript ||
      revoked.summary ||
      (await request(`/api/messages/${mid}/captions`)).length
    )
      throw Error("Consent revocation did not clear generated text");
    console.log(
      "Consent revocation cleared transcript, summary, and captions.",
    );
    await request("/api/account/delete", "POST", { confirmation: "DELETE" });
    process.exit(0);
  }
  if (m.ai_status === "failed") throw Error("AI worker reported failure");
}
throw Error("AI result timed out");
