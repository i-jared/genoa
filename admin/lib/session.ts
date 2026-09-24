import "server-only";
import { cookies } from "next/headers";
import { env } from "./env";
import { openSession, sealSession, SESSION_MAX_AGE_SECONDS } from "./session-crypto";

/** The Worker bearer token for this request, or null when signed out or expired. */
export async function readSessionToken(): Promise<string | null> {
  const { cookieName, sessionSecret } = env();
  const value = (await cookies()).get(cookieName)?.value;
  const session = await openSession(value, sessionSecret);
  return session?.token ?? null;
}

/** Only callable from server actions and route handlers. */
export async function writeSession(token: string): Promise<void> {
  const { cookieName, sessionSecret, secureCookies } = env();
  const value = await sealSession({ token, exp: Date.now() + SESSION_MAX_AGE_SECONDS * 1000 }, sessionSecret);
  (await cookies()).set(cookieName, value, {
    httpOnly: true,
    secure: secureCookies,
    sameSite: "strict",
    path: "/",
    maxAge: SESSION_MAX_AGE_SECONDS,
  });
}

/** Only callable from server actions and route handlers. */
export async function clearSession(): Promise<void> {
  const { cookieName, secureCookies } = env();
  (await cookies()).set(cookieName, "", {
    httpOnly: true,
    secure: secureCookies,
    sameSite: "strict",
    path: "/",
    maxAge: 0,
  });
}
