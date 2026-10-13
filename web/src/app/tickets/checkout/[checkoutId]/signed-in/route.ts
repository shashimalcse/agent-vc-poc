import { NextResponse, type NextRequest } from "next/server";
import { consumeVerification } from "@/lib/checkout";
import { sitePath } from "@/lib/hostRouting";
import { SESSION_COOKIE, checkoutCookie, cookieOptions } from "@/lib/lanternSession";
import { readDb } from "@/lib/store";

// The browser lands here once its wallet sign-in is verified. Turns the verification into a
// signed-in session cookie (single use, only for the browser holding the checkout cookie),
// then continues to Confirm. Relative 303 redirects keep the public host and base intact.
export async function GET(request: NextRequest, ctx: RouteContext<"/tickets/checkout/[checkoutId]/signed-in">) {
  const { checkoutId } = await ctx.params;
  const base = request.headers.get("x-site-base") ?? "/tickets";
  const go = (path: string) => new NextResponse(null, { status: 303, headers: { Location: sitePath(base, path) } });

  const secret = request.cookies.get(checkoutCookie(checkoutId))?.value;
  const result = consumeVerification(checkoutId, secret);
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
