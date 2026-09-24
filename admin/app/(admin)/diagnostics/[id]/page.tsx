import type { Metadata } from "next";
import Link from "next/link";
import { apiPath, load } from "@/lib/api";
import type { Diagnostic } from "@/lib/types";
import { ErrorNotice } from "@/components/ErrorNotice";
import { Json } from "@/components/Json";
import { Time } from "@/components/Time";
import { formatBytes } from "@/components/format";

export const metadata: Metadata = { title: "Diagnostic" };

export default async function DiagnosticPage({ params }: PageProps<"/diagnostics/[id]">) {
  const { id } = await params;
  const result = await load<Diagnostic & { payload: unknown }>(apiPath("admin", "diagnostics", id));
  return (
    <>
      <p><Link href="/diagnostics">← All diagnostics</Link></p>
      {!result.ok ? (
        <ErrorNotice message={result.error} />
      ) : (
        <>
          <h1>{result.data.kind} on {result.data.platform}</h1>
          <section className="card">
            <dl className="facts">
              <div><dt>Received</dt><dd><Time value={result.data.created_at} /></dd></div>
              <div><dt>App</dt><dd>{result.data.app_version} ({result.data.build})</dd></div>
              <div><dt>OS</dt><dd>{result.data.os_version}</dd></div>
              <div><dt>User</dt><dd><Link href={`/users/${encodeURIComponent(result.data.user_id)}`}><code>{result.data.user_id}</code></Link></dd></div>
              <div><dt>Size</dt><dd>{formatBytes(result.data.bytes)}</dd></div>
              <div><dt>Id</dt><dd><code>{result.data.id}</code></dd></div>
            </dl>
          </section>
          <h2>Payload</h2>
          <Json value={result.data.payload} />
        </>
      )}
    </>
  );
}
