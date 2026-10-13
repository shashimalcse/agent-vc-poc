import "server-only";
import { config } from "./config";
import { ACCEPTED_CREDENTIALS, buildSignInDcql } from "./credentialTypes";
import type { Verifier2Info } from "./verifierResponse";

// walt.id verifier-api2 1.1.1 (internal, http://localhost:7004).
//   POST /verification-session/create          -> { sessionId, bootstrapAuthorizationRequestUrl, fullAuthorizationRequestUrl }
//   GET  /verification-session/{id}/info       -> session object (status, policy_results, presented_credentials, ...)
//   GET  /verification-session/{id}/verification-session/events  (SSE; not used, /info is polled)

/** Session setup. Signing key, x5c, client_id and urlPrefix come from verifier-service.conf. */
export function buildSessionSetup() {
  const issuers = [...new Set(ACCEPTED_CREDENTIALS.map((c) => c.issuer))];
  return {
    flow_type: "cross_device",
    core_flow: {
      dcql_query: buildSignInDcql(),
      // Signed request object (JAR) by reference, as x509_san_dns:tickets.localtest.me.
      signed_request: true,
      // false -> response_mode direct_post (true would be direct_post.jwt).
      encrypted_response: false,
      expiration_duration: `PT${config.verificationTtlSeconds}S`,
      policies: {
        vc_policies: [
          "signature",
          "expiration",
          "not-before",
          { policy: "allowed-issuer", allowed_issuer: issuers.length === 1 ? issuers[0] : issuers },
        ],
        vp_policies: {
          "dc+sd-jwt": [
            "dc+sd-jwt/audience-check",
            "dc+sd-jwt/kb-jwt_signature",
            "dc+sd-jwt/nonce-check",
            "dc+sd-jwt/sd_hash-check",
            "dc+sd-jwt/kb-jwt_iat-check",
            "dc+sd-jwt/exp-check",
            "dc+sd-jwt/nbf-check",
          ],
        },
      },
    },
  };
}

export type CreatedSession = { sessionId: string; requestUrl: string };

export async function createSignInSession(): Promise<CreatedSession> {
  const res = await fetch(`${config.verifier2InternalUrl}/verification-session/create`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    cache: "no-store",
    body: JSON.stringify(buildSessionSetup()),
  });
  if (!res.ok) throw new Error(`verifier2 create session failed: ${res.status} ${(await res.text()).slice(0, 300)}`);
  const body = (await res.json()) as {
    sessionId?: string;
    bootstrapAuthorizationRequestUrl?: string;
    fullAuthorizationRequestUrl?: string;
  };
  // The bootstrap URL is the short by-reference form (client_id + request_uri). The "full" URL
  // carries the whole signed request object by value, which makes a dense QR code.
  const requestUrl = body.bootstrapAuthorizationRequestUrl ?? body.fullAuthorizationRequestUrl;
  if (!body.sessionId || !requestUrl?.startsWith("openid4vp://")) {
    throw new Error("verifier2 create session: unexpected response");
  }
  return { sessionId: body.sessionId, requestUrl };
}

/** null when verifier2 no longer knows the session (expired and evicted, or restarted). */
export async function getVerificationInfo(sessionId: string): Promise<Verifier2Info | null> {
  const res = await fetch(`${config.verifier2InternalUrl}/verification-session/${encodeURIComponent(sessionId)}/info`, {
    cache: "no-store",
    signal: AbortSignal.timeout(5000),
  });
  if (res.status === 404) return null;
  if (!res.ok) throw new Error(`verifier2 info failed: ${res.status}`);
  return (await res.json()) as Verifier2Info;
}
