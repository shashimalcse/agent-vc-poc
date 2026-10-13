import { refreshOffer } from "@/lib/registry";
import { readDb } from "@/lib/store";

// Live status of a credential offer for the agent page: { status } only.
// Reads issuer2 server-side; issuer2's session (with its signing key) never reaches the browser.
export async function GET(_request: Request, ctx: RouteContext<"/api/registry/offers/[offerId]">) {
  const { offerId } = await ctx.params;
  const offer = readDb().offers[offerId];
  if (!offer) return Response.json({ status: "not-found" }, { status: 404, headers: { "cache-control": "no-store" } });
  const { status, offer: updated } = await refreshOffer(offer);
  return Response.json(
    { status, ...(updated.issuedAt ? { issuedAt: updated.issuedAt } : {}) },
    { headers: { "cache-control": "no-store" } },
  );
}
