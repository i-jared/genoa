"use client";

// Production hides server error details from the client; the digest lets an
// operator find the matching server log line.
export default function ErrorPage({ error, reset }: { error: Error & { digest?: string }; reset: () => void }) {
  return (
    <main className="page page-narrow">
      <div className="notice notice-error" role="alert">
        <strong>Something went wrong</strong>
        <p>The page could not be shown.{error.digest ? ` Reference: ${error.digest}` : ""}</p>
      </div>
      <button type="button" className="button" onClick={reset}>
        Try again
      </button>
    </main>
  );
}
