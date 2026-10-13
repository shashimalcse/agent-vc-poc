import { advanceVerification } from "@/lib/checkout";
import { sitePath } from "@/lib/hostRouting";

// Status of a wallet sign-in for the verify page. Reads verifier2 server-side and returns only
// { status, message?, nextUrl? }: no verifier2 ids, policies, claims or credentials.
// status: pending | verified | failed | expired. nextUrl (on verified) finishes the sign-in.
export async function GET(request: Request, ctx: RouteContext<"/api/tickets/verification/[id]">) {
  const { id } = await ctx.params;
  const v = await advanceVerification(id);
  if (!v) return Response.json({ status: "not-found" }, { status: 404, headers: { "cache-control": "no-store" } });

  const base = request.headers.get("x-site-base") ?? "/tickets";
  return Response.json(
    {
      status: v.status,
      ...(v.message ? { message: v.message } : {}),
      ...(v.status === "verified" ? { nextUrl: sitePath(base, `/checkout/${v.checkoutId}/signed-in`) } : {}),
    },
    { headers: { "cache-control": "no-store" } },
  );
}
