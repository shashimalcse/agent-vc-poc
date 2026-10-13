import path from "node:path";

// Server-only configuration. Defaults match docs/WIRING.md for local development.
function env(name: string, fallback: string): string {
  const v = process.env[name];
  return v && v.trim() ? v.trim().replace(/\/+$/, "") : fallback;
}

export const config = {
  /** issuer2 management API, never through Caddy. */
  issuer2InternalUrl: env("ISSUER2_INTERNAL_URL", "http://localhost:7005"),
  /** verifier2 session API, never through Caddy. */
  verifier2InternalUrl: env("VERIFIER2_INTERNAL_URL", "http://localhost:7004"),
  registryPublicUrl: env("REGISTRY_PUBLIC_URL", "https://registry.localtest.me"),
  ticketsPublicUrl: env("TICKETS_PUBLIC_URL", "https://tickets.localtest.me"),
  /** The Registry's single operator (no sign-in in the demo). */
  operatorName: env("OPERATOR_NAME", "Ada Lovelace"),
  /** Trust anchor for issuer x5c chains (infra/certs/rootCA.pem). */
  rootCaPath: env("ROOT_CA_PATH", path.resolve(process.cwd(), "../infra/certs/rootCA.pem")),
  dataDir: env("DATA_DIR", path.resolve(process.cwd(), ".data")),
  /** Lifetime of a credential offer (pre-authorized code), in seconds. */
  offerTtlSeconds: Number(env("OFFER_TTL_SECONDS", "600")),
  /** Lifetime of an unused wallet sign-in request, in seconds. */
  verificationTtlSeconds: Number(env("VERIFICATION_TTL_SECONDS", "600")),
  /** How long seats picked at checkout stay reserved, in seconds. */
  seatHoldSeconds: Number(env("SEAT_HOLD_SECONDS", "600")),
} as const;

export const AGENT_VCT = "https://registry.localtest.me/credentials/agent/v1";
export const AGENT_CONFIGURATION_ID = "AgentCredential";
/** issuer2 profile id; equal to the configuration id in infra/waltid/issuer2/config. */
export const AGENT_PROFILE_ID = "AgentCredential";
