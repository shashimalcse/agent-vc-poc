import { advanceVerification } from "@/lib/checkout";

// Status of a wallet sign-in for the verify page. Reads verifier2 server-side and returns only
// { status, message?, nextUrl? }: no verifier2 ids, policies, claims or credentials.
// status: pending | verified | failed | expired. It never finishes the sign-in: only the wallet's
// continuation (verifier2's success redirect with its response_code) does.
export async function GET(_request: Request, ctx: RouteContext<"/api/tickets/verification/[id]">) {
  const { id } = await ctx.params;
  const v = await advanceVerification(id);
  if (!v) return Response.json({ status: "not-found" }, { status: 404, headers: { "cache-control": "no-store" } });

  return Response.json(
    {
      status: v.status,
      ...(v.message ? { message: v.message } : {}),
    },
    { headers: { "cache-control": "no-store" } },
  );
}
