"use server";

import { apiPath } from "@/lib/api";
import { formId, formText, mutate, type ActionState } from "@/lib/mutate";

const ACTIONS = {
  dismiss: "Report dismissed.",
  remove_message: "Message removed and report resolved.",
  remove_and_suspend: "Message removed, author suspended, and report resolved.",
} as const;

export async function resolveReport(_state: ActionState, form: FormData): Promise<ActionState> {
  const id = formId(form, "id");
  const action = form.get("action");
  const note = formText(form, "note", 2000);
  if (!id || typeof action !== "string" || !(action in ACTIONS)) return { ok: false, message: "This report action is not valid." };
  if (note === null) return { ok: false, message: "Keep the note under 2,000 characters." };
  if (action === "remove_and_suspend" && !note) return { ok: false, message: "Give a reason for suspending the author." };
  return mutate(
    apiPath("admin", "reports", id, "resolve"),
    { action, ...(note ? { note } : {}) },
    ACTIONS[action as keyof typeof ACTIONS],
  );
}
