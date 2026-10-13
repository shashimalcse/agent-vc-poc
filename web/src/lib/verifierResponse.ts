// Maps verifier2's GET /verification-session/{id}/info response to what Lantern Tickets needs.
// Pure: no I/O, so it is unit tested against a recorded verifier2 1.1.1 response.
import type { AcceptedCredential } from "./credentialTypes";

/** The fields of verifier2's session object that this app reads. Everything else is ignored. */
export type Verifier2Info = {
  id?: string;
  status?: string;
  statusReason?: string | null;
  failure?: { type?: string; [k: string]: unknown } | null;
  policy_results?: {
    overallSuccess?: boolean;
    vc_policies?: Array<{
      policy?: { id?: string };
      success?: boolean;
      result?: { successful_issuer_public_key?: JsonWebKey } | null;
      query_id?: string;
      credential_index?: number;
    }>;
  } | null;
  presented_credentials?: Record<
    string,
    Array<{
      format?: string;
      issuer?: string;
      credentialData?: Record<string, unknown>;
      signature?: { jwtHeader?: { x5c?: string[]; [k: string]: unknown } };
    }>
  > | null;
};

export type VerificationOutcome =
  | { state: "pending"; verifierStatus: string }
  | { state: "expired" }
  | { state: "failed"; reason: string }
  | {
      state: "success";
      /** Which accepted credential the wallet presented. */
      credential: AcceptedCredential;
      /** The requested claims, all disclosed, as strings. */
      claims: Record<string, string>;
      issuer: string;
      /** Issuer certificate chain from the credential's JWS header (leaf first). */
      x5c: string[];
      /** Key verifier2 used for the issuer-signature policy. */
      issuerPublicKey?: JsonWebKey;
    };

const PENDING = new Set([
  "ACTIVE",
  "UNUSED",
  "IN_USE",
  "VALIDATING_RECEIVED_REQUEST",
  "PROCESSING_FLOW",
  "UNKNOWN",
]);

function str(v: unknown): string | undefined {
  return typeof v === "string" && v.trim() !== "" ? v : undefined;
}

export function mapVerifierInfo(info: Verifier2Info | null, accepted: readonly AcceptedCredential[]): VerificationOutcome {
  if (!info) return { state: "expired" };
  const status = info.status ?? "UNKNOWN";
  if (PENDING.has(status)) return { state: "pending", verifierStatus: status };
  if (status === "EXPIRED") return { state: "expired" };
  if (status === "FAILED") {
    const kind = info.failure?.type ? ` (${info.failure.type})` : "";
    return { state: "failed", reason: `The verifier rejected the presentation${kind}.` };
  }
  if (status !== "SUCCESSFUL") return { state: "failed", reason: `Unexpected verifier status ${status}.` };

  // SUCCESSFUL: still check that every policy passed and that exactly one accepted credential
  // came back with every requested claim. verifier2 reports SUCCESSFUL even when the wallet
  // withholds requested claims.
  if (info.policy_results?.overallSuccess !== true) {
    return { state: "failed", reason: "Not every verification policy passed." };
  }
  const presentedIds = Object.entries(info.presented_credentials ?? {})
    .filter(([, list]) => Array.isArray(list) && list.length > 0)
    .map(([id]) => id);
  const matches = accepted.filter((a) => presentedIds.includes(a.id));
  if (matches.length !== 1 || presentedIds.length !== 1) {
    return { state: "failed", reason: "Expected exactly one accepted credential." };
  }
  const credential = matches[0];
  const presented = info.presented_credentials![credential.id];
  if (presented.length !== 1) return { state: "failed", reason: "Expected exactly one credential." };
  const cred = presented[0];
  if (cred.format !== credential.format) return { state: "failed", reason: "Unexpected credential format." };

  const data = cred.credentialData ?? {};
  if (data.vct !== credential.vct) return { state: "failed", reason: "Unexpected credential type (vct mismatch)." };

  const claims: Record<string, string> = {};
  const missing: string[] = [];
  for (const name of credential.claims) {
    const v = str(data[name]);
    if (v === undefined) missing.push(name);
    else claims[name] = v;
  }
  if (missing.length) return { state: "failed", reason: `The wallet did not share: ${missing.join(", ")}.` };
  const invalid = credential.validate?.(claims);
  if (invalid) return { state: "failed", reason: invalid };

  const issuer = str(data.iss) ?? str(cred.issuer);
  if (!issuer) return { state: "failed", reason: "The credential names no issuer." };

  const x5c = cred.signature?.jwtHeader?.x5c;
  if (!Array.isArray(x5c) || x5c.length === 0) {
    return { state: "failed", reason: "The credential carries no issuer certificate (x5c)." };
  }

  const sig = info.policy_results?.vc_policies?.find(
    (p) =>
      p.policy?.id === "signature" &&
      (p.query_id ?? credential.id) === credential.id &&
      (p.credential_index ?? 0) === 0,
  );

  return {
    state: "success",
    credential,
    claims,
    issuer,
    x5c,
    issuerPublicKey: sig?.success ? (sig.result?.successful_issuer_public_key ?? undefined) : undefined,
  };
}
