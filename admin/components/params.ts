/** Accepts a positive integer millisecond cursor from the query string. */
export function parseBefore(value: string | string[] | undefined): number | null {
  if (typeof value !== "string" || !/^\d{1,16}$/.test(value)) return null;
  const parsed = Number(value);
  return parsed > 0 ? parsed : null;
}
