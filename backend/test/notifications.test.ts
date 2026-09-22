import { test } from "node:test";
import assert from "node:assert/strict";
import { generateKeyPairSync } from "node:crypto";
import { runJob } from "../src/jobs";
import type { Env, Job } from "../src/types";

test("message and reminder delivery preserve the exact message and distinct event IDs", async (t) => {
  const { privateKey } = generateKeyPairSync("rsa", { modulusLength: 2048 });
  const sent: Array<{ data: Record<string, string> }> = [];
  t.mock.method(globalThis, "fetch", async (url: string, init: RequestInit) => {
    if (url.includes("oauth2.googleapis.com")) return Response.json({ access_token: "synthetic-test-token" });
    sent.push(JSON.parse(String(init.body)).message);
    return Response.json({ name: "test-delivery" });
  });
  const env = {
    FCM_SERVICE_ACCOUNT: JSON.stringify({ client_email: "test@example.test", project_id: "test", private_key: privateKey.export({ type: "pkcs8", format: "pem" }) }),
    DB: {
      prepare(sql: string) {
        return {
          bind() { return this; },
          async first() { return sql.includes("FROM messages") ? { id: "message-1", conversation_id: "conversation-1", author_id: "sender", state: "ready" } : { allowed: 1 }; },
          async all() { return { results: sql.includes("FROM devices") ? [{ token: "device-token" }] : [{ user_id: "viewer" }] }; },
        };
      },
    },
    ROOMS: { idFromName: (name: string) => name, get: () => ({ fetch: async () => new Response("ok") }) },
  } as unknown as Env;
  for (const kind of ["message", "reminder"] as const) {
    await runJob(env, { id: `${kind}:event-1`, kind, payload: { messageId: "message-1", userId: "viewer" } } as Job);
  }
  assert.deepEqual(sent.map((message) => message.data), [
    { conversation_id: "conversation-1", message_id: "message-1", event_id: "message:event-1" },
    { conversation_id: "conversation-1", message_id: "message-1", event_id: "reminder:event-1" },
  ]);
});
