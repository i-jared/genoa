import type { Metadata } from "next";
import { load } from "@/lib/api";
import type { FailedJob } from "@/lib/types";
import { ErrorNotice } from "@/components/ErrorNotice";
import { ActionForm } from "@/components/ActionForm";
import { Json } from "@/components/Json";
import { Time } from "@/components/Time";
import { discardJob, retryJob } from "./actions";

export const metadata: Metadata = { title: "Failed jobs" };

export default async function JobsPage() {
  const result = await load<{ jobs: FailedJob[] }>("/admin/jobs/failed");
  return (
    <>
      <h1>Failed jobs</h1>
      {!result.ok ? (
        <ErrorNotice message={result.error} />
      ) : result.data.jobs.length === 0 ? (
        <p className="empty">No failed jobs.</p>
      ) : (
        <div className="stack">
          {result.data.jobs.map((job) => (
            <article key={job.id} className="card">
              <header className="report-head">
                <div>
                  <p className="report-reason"><code>{job.kind}</code></p>
                  <p className="muted small">
                    Failed <Time value={job.failed_at} /> after {job.attempts} {job.attempts === 1 ? "attempt" : "attempts"} · <code>{job.id}</code>
                  </p>
                </div>
              </header>
              <details>
                <summary>Payload</summary>
                <Json value={job.payload} />
              </details>
              <div className="actions">
                <ActionForm action={retryJob} fields={{ id: job.id }} label="Retry" />
                <ActionForm
                  action={discardJob}
                  fields={{ id: job.id }}
                  label="Discard"
                  danger
                  confirm="Discard this job? Its work will not run again."
                />
              </div>
            </article>
          ))}
        </div>
      )}
    </>
  );
}
