import "server-only";
import { getOfferStatus, type OfferStatus } from "./issuer2";
import { mutate, readDb, type Offer } from "./store";

// Agent Registry view state: which agents hold a credential, and the live state of an offer.

/** issuer2 rounds iat/nbf down to 00:00 UTC and adds 90 days for exp (see infra/waltid). */
export function validUntil(issuedAt: string): Date {
  const d = new Date(issuedAt);
  d.setUTCHours(0, 0, 0, 0);
  d.setUTCDate(d.getUTCDate() + 90);
  return d;
}

export function latestOffer(agentId: string, db = readDb()): Offer | undefined {
  return Object.values(db.offers)
    .filter((o) => o.agentId === agentId)
    .sort((a, b) => b.createdAt.localeCompare(a.createdAt))[0];
}

/** Most recent issuance for the agent, if any offer was ever collected. */
export function lastIssued(agentId: string, db = readDb()): Offer | undefined {
  return Object.values(db.offers)
    .filter((o) => o.agentId === agentId && o.issuedAt)
    .sort((a, b) => b.issuedAt!.localeCompare(a.issuedAt!))[0];
}

/** Offer status, persisting "issued" the first time issuer2 reports it. */
export async function refreshOffer(offer: Offer): Promise<{ status: OfferStatus; offer: Offer }> {
  if (offer.issuedAt) return { status: "issued", offer };
  const status = await getOfferStatus(offer.offerId, offer.expiresAt);
  if (status !== "issued") return { status, offer };
  const updated = mutate((db) => {
    const o = db.offers[offer.offerId];
    o.issuedAt ??= new Date().toISOString();
    return o;
  });
  return { status, offer: updated };
}

/** For the agents list: issued, an offer still waiting, or nothing yet. */
export function agentCredentialState(agentId: string, db = readDb()): "issued" | "waiting" | "none" {
  if (lastIssued(agentId, db)) return "issued";
  const offer = latestOffer(agentId, db);
  return offer && offer.expiresAt > Date.now() ? "waiting" : "none";
}
