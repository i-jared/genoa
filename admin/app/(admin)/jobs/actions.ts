"use server";

import { apiPath } from "@/lib/api";
import { formId, mutate, type ActionState } from "@/lib/mutate";

export async function retryJob(_state: ActionState, form: FormData): Promise<ActionState> {
  const id = formId(form, "id");
  if (!id) return { ok: false, message: "This job id is not valid." };
  return mutate(apiPath("admin", "jobs", id, "retry"), {}, "Job queued for retry.");
}

export async function discardJob(_state: ActionState, form: FormData): Promise<ActionState> {
  const id = formId(form, "id");
  if (!id) return { ok: false, message: "This job id is not valid." };
  return mutate(apiPath("admin", "jobs", id, "discard"), {}, "Job discarded.");
}
