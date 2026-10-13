import "server-only";
import { AGENT_PROFILE_ID, config } from "./config";
import type { AgentClaims } from "./agent";

// walt.id issuer-api2 1.1.1 management API (internal, http://localhost:7005).
//   POST /issuer2/credential-offers  -> { offerId, credentialOffer, expiresAt (epoch ms), ... }
//   GET  /issuer2/sessions/{offerId} -> { status: ACTIVE | SUCCESSFUL | UNSUCCESSFUL | ..., isClosed, expiresAt, ... }
// The session object also carries the issuer key and credential data; never pass it on.

export type CreatedOffer = { offerId: string; credentialOffer: string; expiresAt: number };

export async function createAgentOffer(claims: AgentClaims): Promise<CreatedOffer> {
  const res = await fetch(`${config.issuer2InternalUrl}/issuer2/credential-offers`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    cache: "no-store",
    body: JSON.stringify({
      profileId: AGENT_PROFILE_ID,
      authMethod: "PRE_AUTHORIZED",
      valueMode: "BY_REFERENCE",
      expiresInSeconds: config.offerTtlSeconds,
      runtimeOverrides: { credentialData: claims },
    }),
  });
  if (!res.ok) throw new Error(`issuer2 create offer failed: ${res.status} ${await safeText(res)}`);
  const body = (await res.json()) as Partial<CreatedOffer>;
  if (typeof body.offerId !== "string" || typeof body.credentialOffer !== "string") {
    throw new Error("issuer2 create offer: unexpected response");
  }
  if (!body.credentialOffer.startsWith("openid-credential-offer://")) {
    throw new Error("issuer2 create offer: unexpected offer URL scheme");
  }
  return {
    offerId: body.offerId,
    credentialOffer: body.credentialOffer,
    expiresAt: typeof body.expiresAt === "number" ? body.expiresAt : Date.now() + config.offerTtlSeconds * 1000,
  };
}

export type OfferStatus = "issued" | "offer-created" | "expired" | "failed" | "unknown";

/** Maps an issuer2 session status to what the portal shows. */
export function mapIssuerSession(session: { status?: string } | null, expiresAt: number, now = Date.now()): OfferStatus {
  if (!session) return now > expiresAt ? "expired" : "unknown";
  switch (session.status) {
    case "SUCCESSFUL":
      return "issued";
    case "UNSUCCESSFUL":
      return "failed";
    case "EXPIRED":
      return "expired";
    case "ACTIVE":
      return now > expiresAt ? "expired" : "offer-created";
    default:
      return now > expiresAt ? "expired" : "unknown";
  }
}

export async function getOfferStatus(offerId: string, expiresAt: number): Promise<OfferStatus> {
  try {
    const res = await fetch(`${config.issuer2InternalUrl}/issuer2/sessions/${encodeURIComponent(offerId)}`, {
      cache: "no-store",
      signal: AbortSignal.timeout(3000),
    });
    if (res.status === 404) return mapIssuerSession(null, expiresAt);
    if (!res.ok) return "unknown";
    const session = (await res.json()) as { status?: string };
    return mapIssuerSession({ status: session.status }, expiresAt);
  } catch {
    return "unknown";
  }
}

async function safeText(res: Response): Promise<string> {
  try {
    return (await res.text()).slice(0, 300);
  } catch {
    return "";
  }
}
