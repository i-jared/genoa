const dateFormat = new Intl.DateTimeFormat("en-GB", {
  year: "numeric",
  month: "short",
  day: "numeric",
  hour: "2-digit",
  minute: "2-digit",
  timeZone: "UTC",
});

function toDate(value: number | string | null | undefined): Date | null {
  if (value === null || value === undefined || value === "") return null;
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? null : date;
}

/** Times are shown in UTC so every admin reads the same value. */
export function formatTime(value: number | string | null | undefined): string {
  const date = toDate(value);
  return date ? `${dateFormat.format(date)} UTC` : "—";
}

export function isoTime(value: number | string | null | undefined): string | undefined {
  return toDate(value)?.toISOString();
}

export function formatAge(from: number | null | undefined, now: number = Date.now()): string {
  if (from === null || from === undefined) return "—";
  const minutes = Math.max(0, Math.floor((now - from) / 60_000));
  if (minutes < 60) return `${minutes}m`;
  const hours = Math.floor(minutes / 60);
  if (hours < 48) return `${hours}h ${minutes % 60}m`;
  return `${Math.floor(hours / 24)}d ${hours % 24}h`;
}

export function formatBytes(bytes: number | null | undefined): string {
  if (bytes === null || bytes === undefined || !Number.isFinite(bytes)) return "—";
  const units = ["B", "KB", "MB", "GB", "TB", "PB"];
  let value = bytes;
  let unit = 0;
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024;
    unit++;
  }
  return `${value.toFixed(unit === 0 || value >= 100 ? 0 : 1)} ${units[unit]}`;
}

export function formatDuration(ms: number | null | undefined): string | null {
  if (!ms || ms <= 0) return null;
  const seconds = Math.round(ms / 1000);
  return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, "0")}`;
}

export function formatCount(value: number | null | undefined): string {
  return typeof value === "number" ? value.toLocaleString("en-GB") : "—";
}

export const RESOLUTION_LABELS: Record<string, string> = {
  dismissed: "Dismissed",
  removed: "Message removed",
  removed_and_suspended: "Removed and author suspended",
};
