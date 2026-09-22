import { Buffer } from "node:buffer";
import { z } from "zod";
import type { Env } from "./types";

const credentials = z.object({
  team_id: z.string().regex(/^[A-Z0-9]{10}$/),
  key_id: z.string().regex(/^[A-Z0-9]{10}$/),
  bundle_id: z.string().min(1).max(200),
  private_key: z.string().min(1),
});
let cached:
  { configuration: string; token: string; expires: number } | undefined;
const b64 = (value: string | Uint8Array) =>
  Buffer.from(value).toString("base64url");

export async function sendApplePush(
  env: Env,
  device: { token: string; platform?: string },
  notification: {
    title: string;
    body: string;
    conversation_id: string;
    message_id: string;
    event_id: string;
  },
) {
  if (!env.APNS_CREDENTIALS) return;
  const config = credentials.parse(JSON.parse(env.APNS_CREDENTIALS));
  if (
    !cached ||
    cached.configuration !== env.APNS_CREDENTIALS ||
    cached.expires <= Date.now()
  ) {
    const payload = `${b64(JSON.stringify({ alg: "ES256", kid: config.key_id }))}.${b64(JSON.stringify({ iss: config.team_id, iat: Math.floor(Date.now() / 1000) }))}`;
    const key = await crypto.subtle.importKey(
      "pkcs8",
      Buffer.from(
        config.private_key.replace(/-----[^-]+-----/g, "").replace(/\s/g, ""),
        "base64",
      ),
      { name: "ECDSA", namedCurve: "P-256" },
      false,
      ["sign"],
    );
    const signature = await crypto.subtle.sign(
      { name: "ECDSA", hash: "SHA-256" },
      key,
      new TextEncoder().encode(payload),
    );
    cached = {
      configuration: env.APNS_CREDENTIALS,
      token: `${payload}.${b64(new Uint8Array(signature))}`,
      expires: Date.now() + 3000000,
    };
  }
  const host =
    device.platform === "ios-sandbox"
      ? "api.sandbox.push.apple.com"
      : "api.push.apple.com";
  const response = await fetch(
    `https://${host}/3/device/${encodeURIComponent(device.token)}`,
    {
      method: "POST",
      headers: {
        authorization: `bearer ${cached.token}`,
        "apns-topic": config.bundle_id,
        "apns-push-type": "alert",
        "apns-priority": "10",
        "apns-collapse-id": notification.event_id.slice(0, 64),
        "content-type": "application/json",
      },
      body: JSON.stringify({
        aps: {
          alert: { title: notification.title, body: notification.body },
          sound: "default",
        },
        conversation_id: notification.conversation_id,
        message_id: notification.message_id,
        event_id: notification.event_id,
      }),
    },
  );
  if (!response.ok) {
    const result = await response
      .json<{ reason?: string }>()
      .catch(() => ({}) as { reason?: string });
    if (
      response.status === 410 ||
      result.reason === "BadDeviceToken" ||
      result.reason === "DeviceTokenNotForTopic"
    ) {
      await env.DB.prepare("DELETE FROM devices WHERE token=? AND platform=?")
        .bind(device.token, device.platform)
        .run();
    } else {
      if (result.reason === "ExpiredProviderToken") cached = undefined;
      throw new Error("APNS_DELIVERY_FAILED");
    }
  }
}
