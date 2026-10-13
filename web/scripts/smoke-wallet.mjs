#!/usr/bin/env node
// Minimal throwaway holder for smoke-testing issuer2 and verifier2 through Caddy.
// Not the PoC wallet (that is wallet/). Dev only: holder key and credential live in
// web/.data/smoke-holder.json.
//
//   NODE_EXTRA_CA_CERTS=../infra/certs/rootCA.pem node scripts/smoke-wallet.mjs acquire '<openid-credential-offer://...>'
//   NODE_EXTRA_CA_CERTS=../infra/certs/rootCA.pem node scripts/smoke-wallet.mjs present '<openid4vp://...>'
import { createHash, generateKeyPairSync, sign, verify, X509Certificate } from "node:crypto";
import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import path from "node:path";

const STATE = path.resolve(import.meta.dirname, "../.data/smoke-holder.json");
const ROOT_CA = path.resolve(import.meta.dirname, "../../infra/certs/rootCA.pem");

const b64u = (b) => Buffer.from(b).toString("base64url");
const json64 = (o) => b64u(JSON.stringify(o));
const unjson = (s) => JSON.parse(Buffer.from(s, "base64url").toString("utf8"));

function loadState() {
  if (existsSync(STATE)) return JSON.parse(readFileSync(STATE, "utf8"));
  const { privateKey } = generateKeyPairSync("ec", { namedCurve: "P-256" });
  const state = { holderJwk: privateKey.export({ format: "jwk" }), credentials: [] };
  saveState(state);
  return state;
}
function saveState(s) {
  mkdirSync(path.dirname(STATE), { recursive: true });
  writeFileSync(STATE, JSON.stringify(s, null, 2), { mode: 0o600 });
}
function publicJwk(jwk) {
  const { kty, crv, x, y } = jwk;
  return { kty, crv, x, y };
}
function es256(header, payload, jwk) {
  const input = `${json64(header)}.${json64(payload)}`;
  const key = { key: jwk, format: "jwk", dsaEncoding: "ieee-p1363" };
  return `${input}.${b64u(sign("sha256", Buffer.from(input), key))}`;
}

async function getJson(url, init) {
  const res = await fetch(url, init);
  const text = await res.text();
  if (!res.ok) throw new Error(`${init?.method ?? "GET"} ${url} -> ${res.status}: ${text}`);
  return JSON.parse(text);
}

async function acquire(offerUrl) {
  const state = loadState();
  const u = new URL(offerUrl);
  const offer = u.searchParams.get("credential_offer")
    ? JSON.parse(u.searchParams.get("credential_offer"))
    : await getJson(u.searchParams.get("credential_offer_uri"));
  console.log("offer:", JSON.stringify(offer));
  const iss = new URL(offer.credential_issuer);
  const wk = (kind) => `${iss.origin}/.well-known/${kind}${iss.pathname === "/" ? "" : iss.pathname}`;
  const meta = await getJson(wk("openid-credential-issuer"));
  const as = await getJson(wk("oauth-authorization-server"));
  const code = offer.grants["urn:ietf:params:oauth:grant-type:pre-authorized_code"]["pre-authorized_code"];
  const token = await getJson(as.token_endpoint, {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({ grant_type: "urn:ietf:params:oauth:grant-type:pre-authorized_code", "pre-authorized_code": code }),
  });
  console.log("token_type:", token.token_type);
  const { c_nonce } = await getJson(meta.nonce_endpoint, { method: "POST" });
  const proof = es256(
    { typ: "openid4vci-proof+jwt", alg: "ES256", jwk: publicJwk(state.holderJwk) },
    { aud: offer.credential_issuer, iat: Math.floor(Date.now() / 1000), nonce: c_nonce },
    state.holderJwk,
  );
  const configId = offer.credential_configuration_ids[0];
  const cred = await getJson(meta.credential_endpoint, {
    method: "POST",
    headers: { "content-type": "application/json", authorization: `Bearer ${token.access_token}` },
    body: JSON.stringify({ credential_configuration_id: configId, proofs: { jwt: [proof] } }),
  });
  const sdjwt = cred.credentials[0].credential;
  const [jwt, ...disc] = sdjwt.split("~");
  const [h, p] = jwt.split(".");
  const header = unjson(h);
  console.log("issuer JWT header:", JSON.stringify({ ...header, x5c: header.x5c?.map((c) => c.slice(0, 16) + "...") }));
  console.log("issuer JWT payload:", JSON.stringify(unjson(p), null, 1));
  console.log("disclosures:", disc.filter(Boolean).map((d) => unjson(d)));
  state.credentials.push({ sdjwt, configId, issuer: offer.credential_issuer, at: new Date().toISOString() });
  saveState(state);
  console.log("stored credential #" + state.credentials.length);
}

function checkX5c(x5c, expectedDns) {
  const leaf = new X509Certificate(Buffer.from(x5c[0], "base64"));
  const root = new X509Certificate(readFileSync(ROOT_CA));
  const chained = leaf.verify(root.publicKey);
  const sanOk = leaf.checkHost(expectedDns) === expectedDns;
  return { chained, sanOk, leaf };
}

async function present(requestUrl) {
  const state = loadState();
  const u = new URL(requestUrl);
  const clientId = u.searchParams.get("client_id");
  let reqJwt = u.searchParams.get("request");
  if (!reqJwt) {
    const res = await fetch(u.searchParams.get("request_uri"));
    if (!res.ok) throw new Error(`request_uri -> ${res.status}: ${await res.text()}`);
    console.log("request_uri content-type:", res.headers.get("content-type"));
    reqJwt = await res.text();
  }
  const [h, p, s] = reqJwt.split(".");
  const header = unjson(h);
  const req = unjson(p);
  const dns = clientId.replace(/^x509_san_dns:/, "");
  const { chained, sanOk, leaf } = checkX5c(header.x5c, dns);
  const sigOk = verify("sha256", Buffer.from(`${h}.${p}`), { key: leaf.publicKey, dsaEncoding: "ieee-p1363" }, Buffer.from(s, "base64url"));
  console.log("request object:", { alg: header.alg, typ: header.typ, sigOk, chainedToRoot: chained, sanMatches: sanOk, client_id: req.client_id, response_mode: req.response_mode, response_uri: req.response_uri });
  if (!sigOk || !chained || !sanOk || req.client_id !== clientId) throw new Error("request object failed checks");

  const q = req.dcql_query.credentials[0];
  const cred = [...state.credentials].reverse().find((c) => c.sdjwt);
  if (!cred) throw new Error("no credential; run acquire first");
  const [jwt, ...rest] = cred.sdjwt.split("~");
  const discs = rest.filter(Boolean);
  const wanted = new Set(q.claims.map((c) => c.path[0]));
  const chosen = discs.filter((d) => wanted.has(unjson(d)[1]));
  const presented = `${[jwt, ...chosen].join("~")}~`;
  const kb = es256(
    { typ: "kb+jwt", alg: "ES256" },
    { iat: Math.floor(Date.now() / 1000), aud: req.client_id, nonce: req.nonce, sd_hash: b64u(createHash("sha256").update(presented).digest()) },
    state.holderJwk,
  );
  const vpToken = { [q.id]: [presented + kb] };
  const res = await fetch(req.response_uri, {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({ vp_token: JSON.stringify(vpToken), state: req.state }),
  });
  console.log("response_uri ->", res.status, await res.text());
}

const [cmd, arg] = process.argv.slice(2);
if (cmd === "acquire") await acquire(arg);
else if (cmd === "present") await present(arg);
else {
  console.error("usage: smoke-wallet.mjs acquire <offer-url> | present <openid4vp-url>");
  process.exit(2);
}
