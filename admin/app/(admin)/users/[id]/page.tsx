import type { Metadata } from "next";
import { apiPath, load } from "@/lib/api";
import type { UserDetail } from "@/lib/types";
import { ErrorNotice } from "@/components/ErrorNotice";
import { MessagePreview } from "@/components/MessagePreview";
import { ActionForm } from "@/components/ActionForm";
import { Time } from "@/components/Time";
import { RESOLUTION_LABELS, formatBytes, formatCount } from "@/components/format";
import { removeMessage, suspendUser, unsuspendUser } from "../actions";

export const metadata: Metadata = { title: "User" };

export default async function UserPage({ params }: PageProps<"/users/[id]">) {
  const { id } = await params;
  const result = await load<UserDetail>(apiPath("admin", "users", id));
  if (!result.ok) return <ErrorNotice message={result.error} />;
  const { user, messages, reports } = result.data;

  return (
    <>
      <h1>{user.name || user.email}</h1>
      <section className="card">
        <dl className="facts">
          <div><dt>Email</dt><dd>{user.email}</dd></div>
          <div><dt>User id</dt><dd><code>{user.id}</code></dd></div>
          <div><dt>Joined</dt><dd><Time value={user.createdAt} /></dd></div>
          <div><dt>Messages</dt><dd>{formatCount(user.message_count)}</dd></div>
          <div><dt>Storage</dt><dd>{formatBytes(user.storage_bytes)}</dd></div>
          <div><dt>Reports against</dt><dd>{formatCount(user.report_count)}</dd></div>
          <div>
            <dt>Status</dt>
            <dd>
              {user.suspended_at ? (
                <>
                  <span className="tag tag-danger">Suspended</span> since <Time value={user.suspended_at} />
                  {user.suspension_reason ? <p className="small">Reason: {user.suspension_reason}</p> : null}
                </>
              ) : (
                <span className="tag">Active</span>
              )}
            </dd>
          </div>
        </dl>
        <div className="actions">
          {user.suspended_at ? (
            <ActionForm
              action={unsuspendUser}
              fields={{ id: user.id }}
              label="Unsuspend"
              confirm={`Lift the suspension on ${user.email}? They will be able to sign in again.`}
            />
          ) : (
            <ActionForm
              action={suspendUser}
              fields={{ id: user.id }}
              label="Suspend"
              danger
              confirm={`Suspend ${user.email}? This signs them out on every device and ends live sharing.`}
              text={{ name: "reason", label: "Reason", required: true }}
            />
          )}
        </div>
      </section>

      <h2>Reports against this user</h2>
      {reports.length === 0 ? (
        <p className="empty">No reports.</p>
      ) : (
        <div className="table-wrap">
          <table>
            <thead>
              <tr><th>Reported</th><th>Reason</th><th>Status</th><th>Message</th></tr>
            </thead>
            <tbody>
              {reports.map((report) => (
                <tr key={report.id}>
                  <td><Time value={report.created_at} /></td>
                  <td className="wrap">{report.reason}</td>
                  <td>{report.status === "open" ? <span className="tag tag-warn">Open</span> : RESOLUTION_LABELS[report.resolution ?? ""] ?? "Resolved"}</td>
                  <td><code>{report.message_id}</code></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      <h2>Latest messages</h2>
      {messages.length === 0 ? (
        <p className="empty">No messages.</p>
      ) : (
        <div className="message-grid">
          {messages.map((message) => (
            <article key={message.id} className="card">
              <p className="muted small">
                <Time value={message.created_at} /> · <code>{message.id}</code>
              </p>
              <MessagePreview message={message} />
              {message.state !== "deleted" && message.state !== "removed" ? (
                <div className="actions">
                  <ActionForm
                    action={removeMessage}
                    fields={{ id: message.id }}
                    label="Remove message"
                    danger
                    confirm="Remove this message for everyone, including its media? This cannot be undone."
                    text={{ name: "note", label: "Note" }}
                  />
                </div>
              ) : null}
            </article>
          ))}
        </div>
      )}
    </>
  );
}
