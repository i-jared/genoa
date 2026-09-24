// Response shapes of the Worker's /admin API (see the backend contract).

export type AdminMe = { id: string; name: string; email: string };

export type Overview = {
  users: number;
  messages_24h: number;
  storage_bytes: number;
  open_reports: number;
  oldest_open_report_at: number | null;
  failed_jobs: number;
  outbox_pending: number;
  oldest_outbox_at: number | null;
  diagnostics_24h: number;
  suspended_users: number;
};

export type PersonRef = { id: string; name: string; email: string };

export type MessageSummary = {
  id: string;
  kind: string;
  state: string;
  text: string | null;
  created_at: number;
  duration_ms: number | null;
  conversation_id: string;
  asset_id: string | null;
  mime: string | null;
};

export type Resolution = "dismissed" | "removed" | "removed_and_suspended";

export type Report = {
  id: string;
  reason: string;
  created_at: number;
  status: "open" | "resolved";
  resolution: Resolution | null;
  note: string | null;
  resolved_at: number | null;
  reporter: PersonRef;
  message: MessageSummary;
  author: PersonRef & { suspended_at: number | null };
  reports_against_author: number;
};

export type ReportsPage = { reports: Report[]; next_before: number | null };

export type AdminUser = {
  id: string;
  name: string;
  email: string;
  createdAt: number | string;
  suspended_at: number | null;
  suspension_reason: string | null;
  message_count: number;
  storage_bytes: number;
  report_count: number;
};

export type UserDetail = {
  user: AdminUser;
  messages: MessageSummary[];
  reports: { id: string; reason: string; created_at: number; status: string; resolution: Resolution | null; message_id: string }[];
};

export type FailedJob = { id: string; kind: string; payload: unknown; failed_at: number; attempts: number };

export type Diagnostic = {
  id: string;
  user_id: string;
  platform: string;
  app_version: string;
  build: string;
  os_version: string;
  kind: string;
  created_at: number;
  bytes: number;
};

export type DiagnosticsPage = { diagnostics: Diagnostic[]; next_before: number | null };

export type AuditEntry = {
  id: string;
  admin_id: string;
  admin_email: string;
  action: string;
  target_type: string;
  target_id: string;
  note: string | null;
  created_at: number;
};

export type AuditPage = { entries: AuditEntry[]; next_before: number | null };

export type ActionState = { ok: boolean; message: string } | null;
