import type { Metadata } from "next";
import { load } from "@/lib/api";
import type { AuditPage } from "@/lib/types";
import { ErrorNotice } from "@/components/ErrorNotice";
import { OlderLink } from "@/components/OlderLink";
import { Time } from "@/components/Time";
import { parseBefore } from "@/components/params";

export const metadata: Metadata = { title: "Audit log" };

export default async function AuditLogPage({ searchParams }: PageProps<"/audit">) {
  const before = parseBefore((await searchParams).before);
  const result = await load<AuditPage>(`/admin/audit${before ? `?before=${before}` : ""}`);
  return (
    <>
      <h1>Audit log</h1>
      {!result.ok ? (
        <ErrorNotice message={result.error} />
      ) : result.data.entries.length === 0 ? (
        <p className="empty">No admin actions recorded yet.</p>
      ) : (
        <>
          <div className="table-wrap">
            <table>
              <thead>
                <tr><th>When</th><th>Admin</th><th>Action</th><th>Target</th><th>Note</th></tr>
              </thead>
              <tbody>
                {result.data.entries.map((entry) => (
                  <tr key={entry.id}>
                    <td><Time value={entry.created_at} /></td>
                    <td>{entry.admin_email}</td>
                    <td><code>{entry.action}</code></td>
                    <td>{entry.target_type} <code>{entry.target_id}</code></td>
                    <td className="wrap">{entry.note ?? <span className="muted">—</span>}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <OlderLink base="/audit" before={result.data.next_before} isPaged={Boolean(before)} />
        </>
      )}
    </>
  );
}
