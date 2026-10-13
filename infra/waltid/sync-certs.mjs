// Copies the public signing certificates from infra/certs into the walt.id config.
// Private keys are not copied: issuer2 and verifier2 include them from the /certs mount.
//
// Usage (after regenerating a signing cert and running infra/scripts/derive-jwks.mjs):
//   node infra/waltid/sync-certs.mjs
//   docker compose -f infra/docker-compose.yml --profile waltid restart issuer-api2 verifier-api2
import { X509Certificate } from "node:crypto";
import { readFileSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const certs = join(here, "..", "certs");

function leafPem(name) {
  const cert = new X509Certificate(readFileSync(join(certs, `${name}.pem`)));
  const b64 = cert.raw.toString("base64").match(/.{1,64}/g).join("\n");
  return { cert, pem: `-----BEGIN CERTIFICATE-----\n${b64}\n-----END CERTIFICATE-----` };
}

function replaceBlock(file, tag, body) {
  const path = join(here, file);
  const src = readFileSync(path, "utf8");
  const re = new RegExp(`(# BEGIN ${tag}[^\\n]*\\n)[\\s\\S]*?(\\n# END ${tag})`);
  if (!re.test(src)) throw new Error(`${file}: markers for "${tag}" not found`);
  writeFileSync(path, src.replace(re, `$1${body}$2`));
}

const issuer = leafPem("issuer-signing");
replaceBlock("issuer2/config/issuer2-profiles.conf", "issuer x5c", `agentIssuerX5chain = [\n  """${issuer.pem}"""\n]`);
console.log(`issuer2: x5c leaf ${issuer.cert.subjectAltName}, serial ${issuer.cert.serialNumber}`);

const verifier = leafPem("verifier-signing");
replaceBlock("verifier2/config/verifier-service.conf", "verifier x5c", `x5c: ["${verifier.cert.raw.toString("base64")}"]`);
console.log(`verifier2: x5c leaf ${verifier.cert.subjectAltName}, serial ${verifier.cert.serialNumber}`);
