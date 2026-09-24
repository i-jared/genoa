import type { Metadata } from "next";
import Link from "next/link";
import { load } from "@/lib/api";
import type { AdminUser } from "@/lib/types";
import { ErrorNotice } from "@/components/ErrorNotice";
import { Time } from "@/components/Time";
import { formatBytes, formatCount } from "@/components/format";

export const metadata: Metadata = { title: "Users" };

export default async function UsersPage({ searchParams }: PageProps<"/users">) {
  const { q } = await searchParams;
  const query = typeof q === "string" ? q.trim().slice(0, 200) : "";
  const result = query ? await load<{ users: AdminUser[] }>(`/admin/users?${new URLSearchParams({ q: query })}`) : null;

  return (
    <>
      <h1>Users</h1>
      <form className="search" role="search" action="/users">
        <label htmlFor="q" className="visually-hidden">Email or name</label>
        <input id="q" name="q" type="search" defaultValue={query} placeholder="Search by email or name" maxLength={200} />
        <button className="button" type="submit">Search</button>
      </form>
      {!result ? (
        <p className="empty">Search for a person by part of their email address or name.</p>
      ) : !result.ok ? (
        <ErrorNotice message={result.error} />
      ) : result.data.users.length === 0 ? (
        <p className="empty">No users match “{query}”.</p>
      ) : (
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>Name</th>
                <th>Email</th>
                <th>Joined</th>
                <th className="num">Messages</th>
                <th className="num">Storage</th>
                <th className="num">Reports</th>
                <th>Status</th>
              </tr>
            </thead>
            <tbody>
              {result.data.users.map((user) => (
                <tr key={user.id}>
                  <td>
                    <Link href={`/users/${encodeURIComponent(user.id)}`}>{user.name || "(no name)"}</Link>
                  </td>
                  <td>{user.email}</td>
                  <td><Time value={user.createdAt} /></td>
                  <td className="num">{formatCount(user.message_count)}</td>
                  <td className="num">{formatBytes(user.storage_bytes)}</td>
                  <td className="num">{formatCount(user.report_count)}</td>
                  <td>{user.suspended_at ? <span className="tag tag-danger">Suspended</span> : <span className="tag">Active</span>}</td>
                </tr>
              ))}
            </tbody>
          </table>
          {result.data.users.length >= 50 ? <p className="muted small">Showing the first 50 matches. Narrow the search to see others.</p> : null}
        </div>
      )}
    </>
  );
}
