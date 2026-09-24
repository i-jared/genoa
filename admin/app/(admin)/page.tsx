import Link from "next/link";
import { load } from "@/lib/api";
import type { Overview } from "@/lib/types";
import { ErrorNotice } from "@/components/ErrorNotice";
import { formatAge, formatBytes, formatCount } from "@/components/format";

const DAY_MS = 24 * 60 * 60 * 1000;

function Tile({ label, value, detail, href, alert }: { label: string; value: string; detail?: string; href?: string; alert?: boolean }) {
  const body = (
    <>
      <span className="tile-label">{label}</span>
      <span className="tile-value">{value}</span>
      {detail ? <span className={alert ? "tile-detail tile-alert" : "tile-detail"}>{detail}</span> : null}
    </>
  );
  const className = alert ? "tile is-alert" : "tile";
  return href ? (
    <Link href={href} className={className}>
      {body}
    </Link>
  ) : (
    <div className={className}>{body}</div>
  );
}

// Evaluated once per server request; a server component never re-renders on the client.
function ages(o: Overview, now: number = Date.now()) {
  return {
    now,
    staleReport: o.oldest_open_report_at !== null && now - o.oldest_open_report_at > DAY_MS,
    staleOutbox: o.oldest_outbox_at !== null && now - o.oldest_outbox_at > DAY_MS,
  };
}

export default async function OverviewPage() {
  const result = await load<Overview>("/admin/overview");
  if (!result.ok) return <ErrorNotice message={result.error} />;
  const o = result.data;
  const { now, staleReport, staleOutbox } = ages(o);

  return (
    <>
      <h1>Overview</h1>
      <section className="tiles" aria-label="Service health">
        <Tile
          label="Open reports"
          value={formatCount(o.open_reports)}
          detail={o.oldest_open_report_at ? `Oldest waiting ${formatAge(o.oldest_open_report_at, now)}${staleReport ? " — over 24h" : ""}` : "None waiting"}
          href="/reports"
          alert={staleReport}
        />
        <Tile label="Failed jobs" value={formatCount(o.failed_jobs)} href="/jobs" alert={o.failed_jobs > 0} detail={o.failed_jobs > 0 ? "Needs attention" : "All clear"} />
        <Tile
          label="Outbox backlog"
          value={formatCount(o.outbox_pending)}
          detail={o.oldest_outbox_at ? `Oldest ${formatAge(o.oldest_outbox_at, now)}` : "Empty"}
          alert={staleOutbox}
        />
        <Tile label="Storage" value={formatBytes(o.storage_bytes)} />
        <Tile label="Users" value={formatCount(o.users)} href="/users" />
        <Tile label="Suspended users" value={formatCount(o.suspended_users)} />
        <Tile label="Messages, last 24h" value={formatCount(o.messages_24h)} />
        <Tile label="Diagnostics, last 24h" value={formatCount(o.diagnostics_24h)} href="/diagnostics" />
      </section>
    </>
  );
}
