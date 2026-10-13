// Credentials Lantern Tickets accepts for "Sign in with your wallet".
//
// Each entry becomes one DCQL credential query and one option in a single credential set, so
// the wallet presents exactly one of them. Adding a credential type (for example a person
// credential) means adding an entry here: the DCQL query, response mapping, issuer trust check
// and account keying all follow from it.
import { isAccreditationLevel } from "./agent";

export type AcceptedCredential = {
  /** DCQL credential query id; also the key of the vp_token / presented_credentials entry. */
  id: string;
  /** Account kind created from this credential. */
  kind: "agent" | "person";
  /** Shown to people: "account created from an agent credential". */
  label: string;
  format: "dc+sd-jwt";
  vct: string;
  /** Claims requested in the DCQL query; every one must be disclosed. */
  claims: readonly string[];
  /** Only this issuer (SD-JWT `iss`) is trusted for this credential. */
  issuer: string;
  /** DNS SAN that the issuer's x5c leaf must carry. */
  issuerCertHost: string;
  /** Shown as "Accredited by" / "Issued by". */
  issuerName: string;
  /** Extra checks on the disclosed values; return an error message or null. */
  validate?: (claims: Record<string, string>) => string | null;
  /** Stable account key: the same credential subject always finds the same account. */
  accountKey: (claims: Record<string, string>) => string;
  /** Name the account is greeted with. */
  displayName: (claims: Record<string, string>) => string;
};

export const AGENT_CREDENTIAL: AcceptedCredential = {
  id: "agent",
  kind: "agent",
  label: "an agent credential",
  format: "dc+sd-jwt",
  vct: "https://registry.localtest.me/credentials/agent/v1",
  claims: ["agent_id", "agent_name", "provider", "operator", "accreditation_level"],
  issuer: process.env.CREDENTIAL_ISSUER?.trim() || "https://issuer.localtest.me/openid4vci",
  issuerCertHost: process.env.ISSUER_CERT_HOST?.trim() || "issuer.localtest.me",
  issuerName: "Agent Registry",
  validate: (c) => (isAccreditationLevel(c.accreditation_level) ? null : "Unknown accreditation level."),
  accountKey: (c) => `agent:${c.agent_id}`,
  displayName: (c) => c.agent_name,
};

/** In order of preference. Today: the agent credential only. */
export const ACCEPTED_CREDENTIALS: readonly AcceptedCredential[] = [AGENT_CREDENTIAL];

export function acceptedCredential(id: string): AcceptedCredential | undefined {
  return ACCEPTED_CREDENTIALS.find((c) => c.id === id);
}

/**
 * DCQL for wallet sign-in: one credential query per accepted credential, and one required
 * credential set whose options are the alternatives ("any one of these").
 */
export function buildSignInDcql(accepted: readonly AcceptedCredential[] = ACCEPTED_CREDENTIALS) {
  return {
    credentials: accepted.map((c) => ({
      id: c.id,
      format: c.format,
      meta: { vct_values: [c.vct] },
      claims: c.claims.map((name) => ({ path: [name] })),
    })),
    credential_sets: [{ options: accepted.map((c) => [c.id]), required: true }],
  };
}
