import "server-only";
import { cache } from "react";
import { redirect } from "next/navigation";
import { env } from "./env";
import { readSessionToken } from "./session";
import type { AdminMe } from "./types";

const TIMEOUT_MS = 15_000;
const MAX_ERROR_LENGTH = 500;

export class ApiError extends Error {
  constructor(
    readonly status: number,
    message: string,
    readonly code?: string,
  ) {
    super(message);
  }
}

/** Joins encoded path segments so ids can never change the request path. */
export function apiPath(...segments: string[]): string {
  return "/" + segments.map((segment) => encodeURIComponent(segment)).join("/");
}

async function errorFrom(response: Response): Promise<ApiError> {
  let message = `The Genoa API returned ${response.status}.`;
  let code: string | undefined;
  try {
    const body: unknown = await response.json();
    if (typeof body === "object" && body !== null) {
      const record = body as Record<string, unknown>;
      const text = typeof record.error === "string" ? record.error : typeof record.message === "string" ? record.message : null;
      if (text) message = text.slice(0, MAX_ERROR_LENGTH);
      if (typeof record.code === "string") code = record.code.slice(0, 64);
    }
  } catch {
    // Non-JSON error body; keep the generic message.
  }
  return new ApiError(response.status, message, code);
}

/** Calls the Worker with an explicit bearer token. Throws ApiError on non-2xx. */
export async function callWorker<T>(
  token: string,
  path: string,
  init: { method?: "GET" | "POST"; body?: unknown } = {},
): Promise<T> {
  let response: Response;
  try {
    response = await fetch(env().apiUrl + path, {
      method: init.method ?? "GET",
      headers: {
        Authorization: `Bearer ${token}`,
        Accept: "application/json",
        // Better Auth checks Origin on POSTs; ADMIN_URL is the Worker's trusted admin origin.
        Origin: env().adminOrigin,
        ...(init.body !== undefined ? { "Content-Type": "application/json" } : {}),
      },
      body: init.body !== undefined ? JSON.stringify(init.body) : undefined,
      cache: "no-store",
      redirect: "error",
      signal: AbortSignal.timeout(TIMEOUT_MS),
    });
  } catch {
    throw new ApiError(0, "Could not reach the Genoa API. Check that it is running and try again.");
  }
  if (!response.ok) throw await errorFrom(response);
  return (await response.json()) as T;
}

/** The signed-in token, or a redirect to sign-in. */
export async function requireToken(): Promise<string> {
  const token = await readSessionToken();
  if (!token) redirect("/sign-in");
  return token;
}

/**
 * Confirms, once per request, that the session still belongs to an admin.
 * A 401/404 means the session was revoked or the user is no longer an admin.
 */
export const requireAdmin = cache(async (): Promise<{ token: string; me: AdminMe }> => {
  const token = await requireToken();
  try {
    const me = await callWorker<AdminMe>(token, "/admin/me");
    return { token, me };
  } catch (error) {
    if (error instanceof ApiError && (error.status === 401 || error.status === 403 || error.status === 404)) {
      redirect("/session-expired");
    }
    throw error;
  }
});

export type Loaded<T> = { ok: true; data: T } | { ok: false; error: string };

/** Loads admin data for a page, turning API failures into a displayable message. */
export async function load<T>(path: string): Promise<Loaded<T>> {
  let token: string;
  try {
    ({ token } = await requireAdmin());
  } catch (error) {
    if (error instanceof ApiError) return { ok: false, error: error.message };
    throw error;
  }
  try {
    return { ok: true, data: await callWorker<T>(token, path) };
  } catch (error) {
    if (error instanceof ApiError) {
      if (error.status === 401) redirect("/session-expired");
      return { ok: false, error: error.message };
    }
    return { ok: false, error: "Something went wrong loading this page." };
  }
}
