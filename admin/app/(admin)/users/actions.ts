"use server";

import { apiPath } from "@/lib/api";
import { formId, formText, mutate, type ActionState } from "@/lib/mutate";

export async function suspendUser(_state: ActionState, form: FormData): Promise<ActionState> {
  const id = formId(form, "id");
  const reason = formText(form, "reason", 2000);
  if (!id) return { ok: false, message: "This user id is not valid." };
  if (!reason) return { ok: false, message: "Give a reason (up to 2,000 characters) for the suspension." };
  return mutate(apiPath("admin", "users", id, "suspend"), { reason }, "User suspended. Their sessions and devices were signed out.");
}

export async function unsuspendUser(_state: ActionState, form: FormData): Promise<ActionState> {
  const id = formId(form, "id");
  if (!id) return { ok: false, message: "This user id is not valid." };
  return mutate(apiPath("admin", "users", id, "unsuspend"), {}, "Suspension lifted.");
}

export async function removeMessage(_state: ActionState, form: FormData): Promise<ActionState> {
  const id = formId(form, "id");
  const note = formText(form, "note", 2000);
  if (!id) return { ok: false, message: "This message id is not valid." };
  if (note === null) return { ok: false, message: "Keep the note under 2,000 characters." };
  return mutate(apiPath("admin", "messages", id, "remove"), note ? { note } : {}, "Message removed.");
}
