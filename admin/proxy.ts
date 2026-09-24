import { NextResponse, type NextRequest } from "next/server";
import { env } from "./lib/env";
import { openSession } from "./lib/session-crypto";

// Routes reachable without a session. Everything else needs a valid cookie
// here, and each page also re-checks /admin/me with the Worker.
const PUBLIC_PATHS = new Set(["/sign-in", "/session-expired"]);

function contentSecurityPolicy(nonce: string, https: boolean): string {
  const dev = process.env.NODE_ENV === "development";
  return [
    "default-src 'self'",
    `script-src 'self' 'nonce-${nonce}' 'strict-dynamic'${dev ? " 'unsafe-eval'" : ""}`,
    // Dev injects inline styles for hot reload; production only links stylesheets.
    dev ? "style-src 'self' 'unsafe-inline'" : `style-src 'self' 'nonce-${nonce}'`,
    "img-src 'self' data: blob:",
    "media-src 'self' blob:",
    "font-src 'self'",
    `connect-src 'self'${dev ? " ws: wss:" : ""}`,
    "object-src 'none'",
    "base-uri 'none'",
    "form-action 'self'",
    "frame-ancestors 'none'",
    ...(https ? ["upgrade-insecure-requests"] : []),
  ].join("; ");
}

export async function proxy(request: NextRequest) {
  const { pathname } = request.nextUrl;
  const { cookieName, sessionSecret, secureCookies } = env();

  if (!PUBLIC_PATHS.has(pathname)) {
    const session = await openSession(request.cookies.get(cookieName)?.value, sessionSecret);
    if (!session) {
      if (pathname.startsWith("/media/")) {
        return new NextResponse(null, { status: 401, headers: { "Cache-Control": "private, no-store" } });
      }
      return NextResponse.redirect(new URL("/sign-in", request.url));
    }
  }

  // Media responses set their own locked-down CSP (see app/media/[assetId]/route.ts).
  if (pathname.startsWith("/media/")) return NextResponse.next();

  const nonce = btoa(crypto.randomUUID());
  const csp = contentSecurityPolicy(nonce, secureCookies);
  const requestHeaders = new Headers(request.headers);
  requestHeaders.set("x-nonce", nonce);
  requestHeaders.set("Content-Security-Policy", csp);

  const response = NextResponse.next({ request: { headers: requestHeaders } });
  response.headers.set("Content-Security-Policy", csp);
  // Admin pages show private user data; never let a shared cache keep them.
  response.headers.set("Cache-Control", "private, no-store");
  return response;
}

export const config = {
  matcher: [
    {
      source: "/((?!_next/static|_next/image|favicon.ico|robots.txt).*)",
    },
  ],
};
