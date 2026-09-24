import { formatTime, isoTime } from "./format";

export function Time({ value }: { value: number | string | null | undefined }) {
  const iso = isoTime(value);
  return iso ? <time dateTime={iso}>{formatTime(value)}</time> : <span>—</span>;
}
