import type { Metadata } from "next";
import Link from "next/link";
import { load } from "@/lib/api";
import type { Report, ReportsPage } from "@/lib/types";
import { ErrorNotice } from "@/components/ErrorNotice";
import { MessagePreview } from "@/components/MessagePreview";
import { ActionForm } from "@/components/ActionForm";
import { Time } from "@/components/Time";
import { RESOLUTION_LABELS } from "@/components/format";
import { parseBefore } from "@/components/params";
import { OlderLink } from "@/components/OlderLink";
import { resolveReport } from "./actions";

export const metadata: Metadata = { title: "Reports" };

function ReportCard({ report }: { report: Report }) {
  const { author, reporter, message } = report;
  return (
    <article className="card report">
      <header className="report-head">
        <div>
          <p className="report-reason">{report.reason}</p>
          <p className="muted small">
            Reported <Time value={report.created_at} /> by{" "}
            <Link href={`/users/${encodeURIComponent(reporter.id)}`}>{reporter.name || reporter.email}</Link>{" "}
            <span className="muted">({reporter.email})</span>
          </p>
        </div>
        {report.status === "resolved" ? (
          <span className="tag">{report.resolution ? RESOLUTION_LABELS[report.resolution] ?? report.resolution : "Resolved"}</span>
        ) : (
          <span className="tag tag-warn">Open</span>
        )}
      </header>

      <div className="report-body">
        <div className="report-author">
          <span className="label">Author</span>
          <Link href={`/users/${encodeURIComponent(author.id)}`}>{author.name || author.email}</Link>
          <span className="muted small">{author.email}</span>
          <span className="badges">
            {author.suspended_at ? <span className="tag tag-danger">Suspended</span> : null}
            <span className={report.reports_against_author > 1 ? "tag tag-warn" : "tag"}>
              {report.reports_against_author} {report.reports_against_author === 1 ? "report" : "reports"} against
            </span>
          </span>
          <span className="muted small">
            Sent <Time value={message.created_at} />
          </span>
        </div>
        <MessagePreview message={message} />
      </div>

      {report.status === "resolved" ? (
        <p className="muted small">
          Resolved <Time value={report.resolved_at} />
          {report.note ? <> — “{report.note}”</> : null}
        </p>
      ) : (
        <div className="actions">
          <ActionForm
            action={resolveReport}
            fields={{ id: report.id, action: "dismiss" }}
            label="Dismiss"
            confirm="Dismiss this report and leave the message in place?"
            text={{ name: "note", label: "Note" }}
          />
          <ActionForm
            action={resolveReport}
            fields={{ id: report.id, action: "remove_message" }}
            label="Remove message"
            danger
            confirm="Remove this message for everyone, including its media? This cannot be undone."
            text={{ name: "note", label: "Note" }}
          />
          <ActionForm
            action={resolveReport}
            fields={{ id: report.id, action: "remove_and_suspend" }}
            label="Remove + suspend author"
            danger
            confirm={`Remove this message and suspend ${author.email}? They will be signed out everywhere.`}
            text={{ name: "note", label: "Reason for suspension", required: true }}
          />
        </div>
      )}
    </article>
  );
}

export default async function ReportsPage({ searchParams }: PageProps<"/reports">) {
  const params = await searchParams;
  const status = params.status === "resolved" ? "resolved" : "open";
  const before = parseBefore(params.before);
  const query = new URLSearchParams({ status });
  if (before) query.set("before", String(before));
  const result = await load<ReportsPage>(`/admin/reports?${query}`);

  return (
    <>
      <h1>Reports</h1>
      <nav className="tabs" aria-label="Report status">
        <Link href="/reports" aria-current={status === "open" ? "page" : undefined}>Open</Link>
        <Link href="/reports?status=resolved" aria-current={status === "resolved" ? "page" : undefined}>Resolved</Link>
      </nav>
      {!result.ok ? (
        <ErrorNotice message={result.error} />
      ) : result.data.reports.length === 0 ? (
        <p className="empty">{status === "open" ? "No open reports. Nothing is waiting for review." : "No resolved reports yet."}</p>
      ) : (
        <>
          <div className="stack">
            {result.data.reports.map((report) => (
              <ReportCard key={report.id} report={report} />
            ))}
          </div>
          <OlderLink base="/reports" params={{ status: status === "resolved" ? "resolved" : undefined }} before={result.data.next_before} isPaged={Boolean(before)} />
        </>
      )}
    </>
  );
}
