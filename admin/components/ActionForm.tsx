"use client";

import { useActionState, useId, useState } from "react";
import type { ActionState } from "@/lib/types";

type Props = {
  action: (state: ActionState, form: FormData) => Promise<ActionState>;
  /** Identifiers sent with the action, e.g. { id: report.id }. */
  fields: Record<string, string>;
  label: string;
  /** Destructive actions show a second confirmation step before submitting. */
  confirm?: string;
  danger?: boolean;
  /** A free-text field shown in the confirmation step. */
  text?: { name: string; label: string; required?: boolean; maxLength?: number };
};

export function ActionForm({ action, fields, label, confirm, danger, text }: Props) {
  const [state, formAction, pending] = useActionState(action, null);
  const [open, setOpen] = useState(false);
  const textId = useId();
  const needsStep = Boolean(confirm || text);

  if (state?.ok) {
    return (
      <p className="action-result" role="status">
        {state.message}
      </p>
    );
  }

  return (
    <form action={formAction} className={`action-form${open ? " is-open" : ""}`}>
      {Object.entries(fields).map(([name, value]) => (
        <input key={name} type="hidden" name={name} value={value} />
      ))}
      {!needsStep || open ? (
        <>
          {confirm ? <p className="confirm-text">{confirm}</p> : null}
          {text ? (
            <div className="field">
              <label htmlFor={textId}>
                {text.label}
                {text.required ? "" : " (optional)"}
              </label>
              <textarea
                id={textId}
                name={text.name}
                rows={2}
                required={text.required}
                maxLength={text.maxLength ?? 2000}
              />
            </div>
          ) : null}
          <div className="button-row">
            <button type="submit" className={danger ? "button button-danger" : "button"} disabled={pending}>
              {pending ? "Working…" : needsStep ? `Confirm: ${label}` : label}
            </button>
            {needsStep ? (
              <button type="button" className="button button-quiet" onClick={() => setOpen(false)} disabled={pending}>
                Cancel
              </button>
            ) : null}
          </div>
        </>
      ) : (
        <button type="button" className={danger ? "button button-danger-outline" : "button button-outline"} onClick={() => setOpen(true)}>
          {label}
        </button>
      )}
      {state && !state.ok ? (
        <p className="action-error" role="alert">
          {state.message}
        </p>
      ) : null}
    </form>
  );
}
