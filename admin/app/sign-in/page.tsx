import type { Metadata } from "next";
import { SignInForm } from "./SignInForm";

export const metadata: Metadata = { title: "Sign in" };

export default async function SignInPage({ searchParams }: PageProps<"/sign-in">) {
  const { expired } = await searchParams;
  return (
    <main className="signin">
      <div className="signin-card">
        <h1>
          Genoa <span className="accent">admin</span>
        </h1>
        <p className="muted">For Genoa staff only. Sign in with your Genoa account.</p>
        {expired ? (
          <p className="notice" role="status">
            Your session ended or no longer has admin access. Sign in again.
          </p>
        ) : null}
        <SignInForm />
      </div>
    </main>
  );
}
