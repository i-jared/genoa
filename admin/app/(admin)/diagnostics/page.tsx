import type { Metadata } from "next";
import Link from "next/link";
import { load } from "@/lib/api";
import type { DiagnosticsPage } from "@/lib/types";
import { ErrorNotice } from "@/components/ErrorNotice";
import { OlderLink } from "@/components/OlderLink";
import { Time } from "@/components/Time";
import { formatBytes } from "@/components/format";
import { parseBefore } from "@/components/params";

export const metadata: Metadata = { title: "Diagnostics" };

export default async function DiagnosticsListPage({ searchParams }: PageProps<"/diagnostics">) {
  const before = parseBefore((await searchParams).before);
  const result = await load<DiagnosticsPage>(`/admin/diagnostics${before ? `?before=${before}` : ""}`);
  return (
    <>
      <h1>Diagnostics</h1>
      {!result.ok ? (
        <ErrorNotice message={result.error} />
      ) : result.data.diagnostics.length === 0 ? (
        <p className="empty">No diagnostics reported.</p>
      ) : (
        <>
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>Received</th><th>Kind</th><th>Platform</th><th>App</th><th>OS</th><th>User</th><th className="num">Size</th>
                </tr>
              </thead>
              <tbody>
                {result.data.diagnostics.map((d) => (
                  <tr key={d.id}>
                    <td><Link href={`/diagnostics/${encodeURIComponent(d.id)}`}><Time value={d.created_at} /></Link></td>
                    <td><span className={d.kind === "crash" ? "tag tag-danger" : "tag"}>{d.kind}</span></td>
                    <td>{d.platform}</td>
                    <td>{d.app_version} ({d.build})</td>
                    <td>{d.os_version}</td>
                    <td><Link href={`/users/${encodeURIComponent(d.user_id)}`}><code>{d.user_id}</code></Link></td>
                    <td className="num">{formatBytes(d.bytes)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <OlderLink base="/diagnostics" before={result.data.next_before} isPaged={Boolean(before)} />
        </>
      )}
    </>
  );
}
