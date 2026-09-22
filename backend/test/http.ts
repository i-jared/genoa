import { randomInt } from "node:crypto";

// Concurrent local suites model separate clients, rather than tripping the shared-IP
// signup throttle. Never override forwarding headers when targeting a real deployment.
const clientIP = `192.0.2.${randomInt(1, 255)}`;
export const localFetch: typeof fetch = (input, init) => {
  const url = new URL(typeof input === "string" || input instanceof URL ? input : input.url);
  if (!["localhost", "127.0.0.1"].includes(url.hostname)) return fetch(input, init);
  const headers = new Headers(init?.headers);
  const address = url.pathname.startsWith("/auth/sign-") ? `198.18.${randomInt(0, 256)}.${randomInt(1, 255)}` : clientIP;
  headers.set("CF-Connecting-IP", address);
  headers.set("X-Forwarded-For", address);
  return fetch(input, { ...init, headers });
};
