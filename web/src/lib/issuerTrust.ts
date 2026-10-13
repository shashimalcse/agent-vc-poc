// Issuer trust for presented agent credentials.
//
// verifier2 1.1.1's "signature" policy verifies the SD-JWT with the key in the x5c leaf, but
// has no policy that chains x5c to a configured root (only ETSI trust lists / VICAL). So
// Lantern Tickets checks the chain itself: leaf -> rootCA.pem, SAN, validity, and that the
// leaf key is the key verifier2 verified the signature with.
import { X509Certificate } from "node:crypto";

export type TrustResult = { ok: true } | { ok: false; reason: string };

export function checkIssuerTrust(input: {
  x5c: string[];
  rootPem: string;
  expectedHost: string;
  expectedIssuer: string;
  issuer: string;
  /** Key verifier2 reported for the successful signature check. */
  verifiedKey?: JsonWebKey;
  now?: Date;
}): TrustResult {
  if (input.issuer !== input.expectedIssuer) {
    return { ok: false, reason: `Untrusted issuer ${input.issuer}.` };
  }
  let chain: X509Certificate[];
  let root: X509Certificate;
  try {
    chain = input.x5c.map((c) => new X509Certificate(Buffer.from(c, "base64")));
    root = new X509Certificate(input.rootPem);
  } catch {
    return { ok: false, reason: "Unreadable issuer certificate." };
  }
  const now = input.now ?? new Date();
  const leaf = chain[0];

  // Walk leaf -> ... -> last; the last element must be signed by (or be) the trust anchor.
  for (let i = 0; i < chain.length; i++) {
    const cert = chain[i];
    if (new Date(cert.validFrom) > now || new Date(cert.validTo) < now) {
      return { ok: false, reason: "Issuer certificate is not currently valid." };
    }
    const next = chain[i + 1];
    if (next) {
      if (!cert.verify(next.publicKey)) return { ok: false, reason: "Broken issuer certificate chain." };
      continue;
    }
    const anchored = cert.fingerprint256 === root.fingerprint256 || cert.verify(root.publicKey);
    if (!anchored) return { ok: false, reason: "Issuer certificate does not chain to the trusted root." };
  }

  if (leaf.checkHost(input.expectedHost) !== input.expectedHost) {
    return { ok: false, reason: `Issuer certificate is not for ${input.expectedHost}.` };
  }

  if (input.verifiedKey) {
    const leafJwk = leaf.publicKey.export({ format: "jwk" });
    if (leafJwk.x !== input.verifiedKey.x || leafJwk.y !== input.verifiedKey.y) {
      return { ok: false, reason: "Signature key does not match the issuer certificate." };
    }
  } else {
    return { ok: false, reason: "The verifier reported no issuer signature key." };
  }
  return { ok: true };
}
