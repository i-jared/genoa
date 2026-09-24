import Link from "next/link";

export default function NotFound() {
  return (
    <main className="page page-narrow">
      <h1>Page not found</h1>
      <p>
        <Link href="/">Back to the overview</Link>
      </p>
    </main>
  );
}
