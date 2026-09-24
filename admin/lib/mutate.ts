import "server-only";
import { redirect } from "next/navigation";
import { revalidatePath } from "next/cache";
import { ApiError, callWorker, requireToken } from "./api";

import type { ActionState } from "./types";

export type { ActionState };

const ID_PATTERN = /^[A-Za-z0-9_.:-]{1,128}$/;

export function formId(form: FormData, name: string): string | null {
  const value = form.get(name);
  return typeof value === "string" && ID_PATTERN.test(value) ? value : null;
}

/** Trimmed text field, or null when missing or longer than `max`. */
export function formText(form: FormData, name: string, max: number): string | null {
  const value = form.get(name);
  if (value === null) return "";
  if (typeof value !== "string") return null;
  const trimmed = value.trim();
  return trimmed.length <= max ? trimmed : null;
}

/** POSTs an admin mutation and refreshes the affected pages. */
export async function mutate(
  path: string,
  body: unknown,
  success: string,
): Promise<ActionState> {
  const token = await requireToken();
  try {
    await callWorker(token, path, { method: "POST", body });
  } catch (error) {
    if (error instanceof ApiError) {
      if (error.status === 401) redirect("/session-expired");
      return { ok: false, message: error.message };
    }
    return { ok: false, message: "The action failed unexpectedly. Try again." };
  }
  // Admin pages are all dynamic; refresh whichever one issued the action.
  revalidatePath("/", "layout");
  return { ok: true, message: success };
}
