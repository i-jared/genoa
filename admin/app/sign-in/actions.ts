"use server";

import { redirect } from "next/navigation";
import { env } from "@/lib/env";
import { ApiError, callWorker } from "@/lib/api";
import { clearSession, readSessionToken, writeSession } from "@/lib/session";
import type { AdminMe } from "@/lib/types";

export type SignInState = { error: string } | null;

const NOT_AUTHORISED = "This account is not authorised to use Genoa admin.";

async function revoke(token: string): Promise<void> {
  try {
    await callWorker(token, "/auth/sign-out", { method: "POST", body: {} });
  } catch {
    // Best effort: the session expires on its own if revocation fails.
  }
}

export async function signIn(_previous: SignInState, form: FormData): Promise<SignInState> {
  const email = form.get("email");
  const password = form.get("password");
  if (typeof email !== "string" || typeof password !== "string" || !email.trim() || !password) {
    return { error: "Enter your email and password." };
  }
  if (email.length > 320 || password.length > 1024) return { error: "Email or password is incorrect." };

  const { apiUrl, adminOrigin } = env();
  let response: Response;
  try {
    response = await fetch(`${apiUrl}/auth/sign-in/email`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Accept: "application/json",
        // The Worker only accepts browser-style sign-ins from trusted origins.
        Origin: adminOrigin,
      },
      body: JSON.stringify({ email: email.trim(), password }),
      cache: "no-store",
      redirect: "error",
      signal: AbortSignal.timeout(15_000),
    });
  } catch {
    return { error: "Could not reach the Genoa API. Try again shortly." };
  }

  if (response.status === 429) return { error: "Too many attempts. Wait a minute and try again." };
  if (response.status === 403) return { error: NOT_AUTHORISED };
  if (!response.ok) {
    return { error: response.status >= 500 ? "The Genoa API had a problem. Try again shortly." : "Email or password is incorrect." };
  }
  const token = response.headers.get("set-auth-token");
  if (!token) return { error: "The Genoa API did not return a session. Check that ADMIN_URL is trusted by the Worker." };

  try {
    await callWorker<AdminMe>(token, "/admin/me");
  } catch (error) {
    // Do not keep a session for a non-admin, and do not say why.
    await revoke(token);
    if (error instanceof ApiError && error.status === 0) return { error: error.message };
    return { error: NOT_AUTHORISED };
  }

  await writeSession(token);
  redirect("/");
}

export async function signOut(): Promise<void> {
  const token = await readSessionToken();
  if (token) await revoke(token);
  await clearSession();
  redirect("/sign-in");
}
