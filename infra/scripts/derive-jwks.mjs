// Derives JWK and x5c forms of the signing keys in infra/certs for walt.id config.
// Usage: node infra/scripts/derive-jwks.mjs
import { createPrivateKey, X509Certificate, createHash } from "node:crypto";
import { readFileSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const certs = join(dirname(fileURLToPath(import.meta.url)), "..", "certs");

for (const name of ["issuer-signing", "verifier-signing"]) {
  const jwk = createPrivateKey(readFileSync(join(certs, `${name}-key.pem`))).export({ format: "jwk" });
  const thumbprint = createHash("sha256")
    .update(JSON.stringify({ crv: jwk.crv, kty: jwk.kty, x: jwk.x, y: jwk.y }))
    .digest("base64url");
  const privateJwk = { kty: jwk.kty, crv: jwk.crv, x: jwk.x, y: jwk.y, d: jwk.d, kid: thumbprint };
  const publicJwk = { kty: jwk.kty, crv: jwk.crv, x: jwk.x, y: jwk.y, kid: thumbprint };
  const leaf = new X509Certificate(readFileSync(join(certs, `${name}.pem`)));
  const root = new X509Certificate(readFileSync(join(certs, "rootCA.pem")));
  const x5c = [leaf.raw.toString("base64"), root.raw.toString("base64")];
  writeFileSync(join(certs, `${name}.jwk.json`), JSON.stringify(privateJwk, null, 2) + "\n", { mode: 0o600 });
  writeFileSync(join(certs, `${name}.public.jwk.json`), JSON.stringify(publicJwk, null, 2) + "\n");
  writeFileSync(join(certs, `${name}.x5c.json`), JSON.stringify(x5c, null, 2) + "\n");
  console.log(`${name}: kid=${thumbprint}`);
}
