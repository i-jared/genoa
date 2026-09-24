const MAX_CHARS = 200_000;

/** Pretty-prints untrusted JSON as text; React escapes it, so nothing is executed. */
export function Json({ value }: { value: unknown }) {
  let text: string;
  try {
    text = JSON.stringify(value, null, 2) ?? "null";
  } catch {
    text = "(unprintable payload)";
  }
  const truncated = text.length > MAX_CHARS;
  return (
    <pre className="json">
      {truncated ? `${text.slice(0, MAX_CHARS)}\n… truncated (${text.length.toLocaleString("en-GB")} characters)` : text}
    </pre>
  );
}
