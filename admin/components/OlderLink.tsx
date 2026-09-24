import Link from "next/link";

type Props = {
  base: string;
  params?: Record<string, string | undefined>;
  before: number | null;
  /** When already past the first page, offer a way back to the newest items. */
  isPaged: boolean;
};

export function OlderLink({ base, params = {}, before, isPaged }: Props) {
  const make = (cursor?: number) => {
    const query = new URLSearchParams();
    for (const [key, value] of Object.entries(params)) if (value) query.set(key, value);
    if (cursor) query.set("before", String(cursor));
    const text = query.toString();
    return text ? `${base}?${text}` : base;
  };
  if (!before && !isPaged) return null;
  return (
    <nav className="pager" aria-label="Pages">
      {isPaged ? <Link href={make()}>← Newest</Link> : <span />}
      {before ? <Link href={make(before)}>Older →</Link> : <span className="muted">End of list</span>}
    </nav>
  );
}
