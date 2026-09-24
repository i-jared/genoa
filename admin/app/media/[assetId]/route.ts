import { type NextRequest } from "next/server";
import { env } from "@/lib/env";
import { readSessionToken } from "@/lib/session";

const ASSET_ID = /^[A-Za-z0-9_.:-]{1,128}$/;
const RANGE = /^bytes=\d*-\d*(,\s*\d*-\d*)*$/;
const PASSTHROUGH = ["content-length", "content-range", "accept-ranges", "etag", "last-modified"];

function empty(status: number): Response {
  return new Response(null, { status, headers: { "Cache-Control": "private, no-store" } });
}

// Streams media through this server so the Worker token stays in the
// httpOnly cookie and never appears in a URL or in client JS.
export async function GET(request: NextRequest, context: RouteContext<"/media/[assetId]">) {
  const { assetId } = await context.params;
  if (!ASSET_ID.test(assetId)) return empty(400);
  const token = await readSessionToken();
  if (!token) return empty(401);

  const headers: Record<string, string> = { Authorization: `Bearer ${token}` };
  const range = request.headers.get("range");
  if (range && range.length <= 200 && RANGE.test(range)) headers.Range = range;

  let upstream: Response;
  try {
    upstream = await fetch(`${env().apiUrl}/admin/media/${encodeURIComponent(assetId)}`, {
      headers,
      cache: "no-store",
      redirect: "error",
      signal: request.signal,
    });
  } catch {
    return empty(502);
  }
  if (!upstream.ok || !upstream.body) {
    await upstream.body?.cancel();
    return empty(upstream.status === 416 ? 416 : upstream.ok ? 502 : upstream.status);
  }

  const out = new Headers({
    "Cache-Control": "private, no-store",
    "X-Content-Type-Options": "nosniff",
    // If the bytes are ever opened directly, they must not run as a document.
    "Content-Security-Policy": "default-src 'none'; media-src 'self'; img-src 'self'; sandbox",
    "Content-Disposition": "inline",
    "Referrer-Policy": "no-referrer",
  });
  const type = upstream.headers.get("content-type") ?? "";
  out.set("Content-Type", /^(video|audio|image)\/[\w.+-]+$/i.test(type.split(";")[0].trim()) ? type : "application/octet-stream");
  for (const name of PASSTHROUGH) {
    const value = upstream.headers.get(name);
    if (value) out.set(name, value);
  }
  return new Response(upstream.body, { status: upstream.status, headers: out });
}
