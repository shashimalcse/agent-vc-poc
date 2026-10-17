import { NextResponse, type NextRequest } from "next/server";
import { advanceVerification, consumeVerification } from "@/lib/checkout";
import { sitePath } from "@/lib/hostRouting";
import { SESSION_COOKIE, checkoutCookie, cookieOptions } from "@/lib/lanternSession";
import { readDb } from "@/lib/store";

// The browser lands here through the wallet's continuation: verifier2's success redirect with a
// fresh response_code. Turns the verification into a signed-in session cookie (single use, only for
// the browser holding the checkout cookie and the response_code), then continues to Confirm.
// Relative 303 redirects keep the public host and base intact.
export async function GET(request: NextRequest, ctx: RouteContext<"/tickets/checkout/[checkoutId]/signed-in">) {
  const { checkoutId } = await ctx.params;
  const base = request.headers.get("x-site-base") ?? "/tickets";
  const go = (path: string) => new NextResponse(null, { status: 303, headers: { Location: sitePath(base, path) } });

  const secret = request.cookies.get(checkoutCookie(checkoutId))?.value;
  // The agent may open the continuation before anything has polled verifier2: settle the sign-in first.
  const verificationId = readDb().checkouts[checkoutId]?.verificationId;
  if (verificationId) await advanceVerification(verificationId);
  const result = consumeVerification(checkoutId, secret, request.nextUrl.searchParams.get("response_code"));
  if (!result.ok) {
    // Already signed in on this browser (e.g. a reload after the session was created)?
    const sid = request.cookies.get(SESSION_COOKIE)?.value;
    if (sid && readDb().lanternSessions[sid]) return go(`/checkout/${checkoutId}/confirm`);
    return go(`/checkout/${checkoutId}/verify?error=${encodeURIComponent(result.error)}`);
  }

  const res = go(`/checkout/${checkoutId}/confirm`);
  const secure = (request.headers.get("x-forwarded-proto") ?? "").split(",")[0].trim() === "https";
  res.cookies.set(SESSION_COOKIE, result.sessionId, cookieOptions(secure, 60 * 60 * 24 * 7));
  return res;
}
