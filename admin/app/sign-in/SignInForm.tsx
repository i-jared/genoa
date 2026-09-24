"use client";

import { useActionState } from "react";
import { signIn } from "./actions";

export function SignInForm() {
  const [state, action, pending] = useActionState(signIn, null);
  return (
    <form action={action} className="stack">
      <div className="field">
        <label htmlFor="email">Email</label>
        <input id="email" name="email" type="email" autoComplete="username" required maxLength={320} autoFocus />
      </div>
      <div className="field">
        <label htmlFor="password">Password</label>
        <input id="password" name="password" type="password" autoComplete="current-password" required maxLength={1024} />
      </div>
      {state?.error ? (
        <p className="action-error" role="alert">
          {state.error}
        </p>
      ) : null}
      <button type="submit" className="button" disabled={pending}>
        {pending ? "Signing in…" : "Sign in"}
      </button>
    </form>
  );
}
