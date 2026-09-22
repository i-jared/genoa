import { Hono } from "hono";
import { z } from "zod";
import { auth } from "./auth";
import { body, fail, rateLimit } from "./access";
import type { AppEnv, Env } from "./types";

export function socialProviders(env: Env): ("google" | "apple")[] {
  return [
    env.GOOGLE_CLIENT_ID && env.GOOGLE_CLIENT_SECRET ? "google" : null,
    env.APPLE_CLIENT_ID && env.APPLE_CLIENT_SECRET ? "apple" : null,
  ].filter((p): p is "google" | "apple" => p !== null);
}
export async function proofChallenge(verifier: string): Promise<string> {
  const hash = await crypto.subtle.digest(
    "SHA-256",
    new TextEncoder().encode(verifier),
  );
  return btoa(String.fromCharCode(...new Uint8Array(hash)))
    .replaceAll("+", "-")
    .replaceAll("/", "_")
    .replaceAll("=", "");
}
type Login = {
  id: string;
  provider: "google" | "apple";
  challenge: string;
  browser: string | null;
  session_cookie: string | null;
  user_id: string | null;
  expires_at: number;
};
export const mobileAuth = new Hono<AppEnv>();
mobileAuth.use("*", async (c, next) => {
  c.header("Cache-Control", "no-store");
  c.header("Referrer-Policy", "no-referrer");
  await rateLimit(
    c.env,
    `mobile-auth:${c.req.header("cf-connecting-ip") ?? "local"}`,
    20,
  );
  await next();
});
mobileAuth.get("/providers", (c) =>
  c.json({ providers: socialProviders(c.env) }),
);
mobileAuth.post("/start", async (c) => {
  const input = await body(
    c,
    z.object({
      provider: z.enum(["google", "apple"]),
      challenge: z.string().regex(/^[A-Za-z0-9_-]{43}$/),
    }),
  );
  if (!socialProviders(c.env).includes(input.provider))
    fail(503, "This sign-in option is not configured yet. Use email for now.");
  const id = crypto.randomUUID();
  await c.env.DB.batch([
    c.env.DB.prepare("DELETE FROM mobile_logins WHERE expires_at<?").bind(
      Date.now(),
    ),
    c.env.DB.prepare(
      "INSERT INTO mobile_logins(id,provider,challenge,expires_at) VALUES(?,?,?,?)",
    ).bind(id, input.provider, input.challenge, Date.now() + 10 * 60_000),
  ]);
  return c.json({ url: `${c.env.APP_URL}/auth/mobile/authorize?id=${id}`, id });
});
mobileAuth.get("/authorize", async (c) => {
  const query = z.string().uuid().safeParse(c.req.query("id"));
  if (!query.success) fail(400, "Invalid sign-in request.");
  const id = query.data;
  const browser = crypto.randomUUID();
  const request = await c.env.DB.prepare(
    "UPDATE mobile_logins SET browser=? WHERE id=? AND browser IS NULL AND expires_at>? RETURNING *",
  )
    .bind(browser, id, Date.now())
    .first<Login>();
  if (!request)
    fail(
      410,
      "This sign-in attempt has expired. Return to Genoa and try again.",
    );
  const response = await auth(c.env).api.signInSocial({
    body: {
      provider: request.provider,
      callbackURL: `${c.env.APP_URL}/auth/mobile/complete?id=${id}`,
      errorCallbackURL: `${c.env.APP_URL}/auth/mobile/failed`,
      disableRedirect: true,
    },
    headers: c.req.raw.headers,
    asResponse: true,
  });
  if (!response.ok) return response;
  const result = (await response.json()) as { url?: string };
  if (!result.url) fail(502, "Could not start sign-in. Please try again.");
  const headers = new Headers(response.headers);
  headers.delete("Content-Type");
  headers.set("Location", result.url);
  headers.set("Cache-Control", "no-store");
  // Bind completion to the browser that actually began this request.
  headers.append(
    "Set-Cookie",
    `genoa_login=${browser}; HttpOnly; SameSite=Lax; Path=/auth/mobile; Max-Age=600${c.env.APP_URL.startsWith("https:") ? "; Secure" : ""}`,
  );
  return new Response(null, { status: 302, headers });
});
mobileAuth.get("/failed", (c) => c.redirect("genoa://auth?error=cancelled"));
mobileAuth.get("/complete", async (c) => {
  const query = z.string().uuid().safeParse(c.req.query("id"));
  if (!query.success) fail(400, "Invalid sign-in request.");
  const id = query.data;
  const cookies = new Map(
    (c.req.header("Cookie") ?? "").split(";").map((x) => {
      const i = x.indexOf("=");
      return [x.slice(0, i).trim(), x.slice(i + 1)];
    }),
  );
  const browser = cookies.get("genoa_login");
  const token =
    cookies.get("__Secure-better-auth.session_token") ??
    cookies.get("better-auth.session_token");
  const session = await auth(c.env).api.getSession({
    headers: c.req.raw.headers,
  });
  if (!session || !token || !browser)
    fail(401, "Sign-in could not be completed. Return to Genoa and try again.");
  const result = await c.env.DB.prepare(
    "UPDATE mobile_logins SET session_cookie=?,user_id=?,expires_at=? WHERE id=? AND browser=? AND session_cookie IS NULL AND expires_at>? RETURNING id",
  )
    .bind(token, session.user.id, Date.now() + 60_000, id, browser, Date.now())
    .first();
  if (!result)
    fail(
      410,
      "This sign-in attempt has expired. Return to Genoa and try again.",
    );
  c.header(
    "Set-Cookie",
    "genoa_login=; HttpOnly; SameSite=Lax; Path=/auth/mobile; Max-Age=0",
  );
  return c.redirect(`genoa://auth?code=${id}`);
});
mobileAuth.post("/exchange", async (c) => {
  const input = await body(
    c,
    z.object({
      code: z.string().uuid(),
      verifier: z.string().regex(/^[A-Za-z0-9._~-]{43,128}$/),
    }),
  );
  const challenge = await proofChallenge(input.verifier);
  // Consume once, atomically, only after the native app proves it started this request.
  const login = await c.env.DB.prepare(
    "DELETE FROM mobile_logins WHERE id=? AND challenge=? AND session_cookie IS NOT NULL AND expires_at>? RETURNING *",
  )
    .bind(input.code, challenge, Date.now())
    .first<Login>();
  if (!login?.session_cookie) fail(401, "Sign-in expired. Please try again.");
  const session = await auth(c.env).api.getSession({
    headers: new Headers({ Authorization: `Bearer ${login.session_cookie}` }),
  });
  if (!session || session.user.id !== login.user_id)
    fail(401, "Sign-in expired. Please try again.");
  c.header("set-auth-token", login.session_cookie);
  return c.json({ user: { id: session.user.id }, token: login.session_cookie });
});
