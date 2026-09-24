import { NextResponse, type NextRequest } from "next/server";
import { clearSession } from "@/lib/session";

// Pages cannot delete cookies while rendering, so a revoked or non-admin
// session is sent here to be cleared before returning to sign-in.
export async function GET(request: NextRequest) {
  await clearSession();
  return NextResponse.redirect(new URL("/sign-in?expired=1", request.url), { headers: { "Cache-Control": "no-store" } });
}
