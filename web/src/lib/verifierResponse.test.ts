import { existsSync, readFileSync } from "node:fs";
import path from "node:path";
import { describe, expect, it } from "vitest";
import { ACCEPTED_CREDENTIALS, AGENT_CREDENTIAL, type AcceptedCredential } from "./credentialTypes";
import { checkIssuerTrust } from "./issuerTrust";
import { mapVerifierInfo, type Verifier2Info } from "./verifierResponse";
// Recorded from verifier-api2 1.1.1 after a real presentation (trimmed, no holder secrets).
import recorded from "./__fixtures__/verifier2-info-successful.json";

const ISSUER = "https://issuer.localtest.me/openid4vci";
const clone = (): Verifier2Info => structuredClone(recorded) as Verifier2Info;

describe("mapVerifierInfo", () => {
  it("maps a successful verifier2 session to the presented credential and its claims", () => {
    const out = mapVerifierInfo(clone(), ACCEPTED_CREDENTIALS);
    expect(out.state).toBe("success");
    if (out.state !== "success") return;
    expect(out.credential.id).toBe("agent");
    expect(out.claims).toEqual({
      agent_id: "agt_test01",
      agent_name: "Test Agent",
      provider: "OpenClaw",
      operator: "Alice",
      accreditation_level: "verified",
    });
    expect(out.credential.accountKey(out.claims)).toBe("agent:agt_test01");
    expect(out.issuer).toBe(ISSUER);
    expect(out.x5c.length).toBeGreaterThan(0);
    expect(out.issuerPublicKey?.crv).toBe("P-256");
  });

  it("picks whichever accepted option was presented", () => {
    const person: AcceptedCredential = {
      ...AGENT_CREDENTIAL,
      id: "person",
      kind: "person",
      vct: "https://example.test/person",
      claims: ["given_name"],
      accountKey: (c) => `person:${c.given_name}`,
      displayName: (c) => c.given_name,
    };
    const out = mapVerifierInfo(clone(), [person, AGENT_CREDENTIAL]);
    expect(out.state === "success" && out.credential.id).toBe("agent");
    expect(mapVerifierInfo(clone(), [person]).state).toBe("failed");
  });

  it("treats in-progress statuses as pending", () => {
    for (const status of ["ACTIVE", "UNUSED", "IN_USE", "PROCESSING_FLOW"]) {
      expect(mapVerifierInfo({ status }, ACCEPTED_CREDENTIALS)).toEqual({ state: "pending", verifierStatus: status });
    }
  });

  it("maps EXPIRED and a vanished session to expired", () => {
    expect(mapVerifierInfo({ status: "EXPIRED" }, ACCEPTED_CREDENTIALS)).toEqual({ state: "expired" });
    expect(mapVerifierInfo(null, ACCEPTED_CREDENTIALS)).toEqual({ state: "expired" });
  });

  it("maps FAILED to failed with the failure type", () => {
    const out = mapVerifierInfo({ status: "FAILED", failure: { type: "vc_policy_violations" } }, ACCEPTED_CREDENTIALS);
    expect(out).toMatchObject({ state: "failed" });
    if (out.state === "failed") expect(out.reason).toContain("vc_policy_violations");
  });

  it("refuses SUCCESSFUL when policies did not all pass", () => {
    const info = clone();
    info.policy_results!.overallSuccess = false;
    expect(mapVerifierInfo(info, ACCEPTED_CREDENTIALS).state).toBe("failed");
  });

  it("refuses a different credential type", () => {
    const info = clone();
    info.presented_credentials!.agent[0].credentialData!.vct = "https://example.com/other";
    expect(mapVerifierInfo(info, ACCEPTED_CREDENTIALS).state).toBe("failed");
  });

  // verifier2 1.1.1 reports SUCCESSFUL even when requested claims were withheld.
  it("refuses when a requested claim was withheld", () => {
    const info = clone();
    delete info.presented_credentials!.agent[0].credentialData!.operator;
    const out = mapVerifierInfo(info, ACCEPTED_CREDENTIALS);
    expect(out).toMatchObject({ state: "failed" });
    if (out.state === "failed") expect(out.reason).toContain("operator");
  });

  it("refuses an unknown accreditation level", () => {
    const info = clone();
    info.presented_credentials!.agent[0].credentialData!.accreditation_level = "platinum";
    expect(mapVerifierInfo(info, ACCEPTED_CREDENTIALS).state).toBe("failed");
  });
});

const rootCa = path.resolve(import.meta.dirname, "../../../infra/certs/rootCA.pem");

describe.skipIf(!existsSync(rootCa))("checkIssuerTrust (against infra/certs/rootCA.pem)", () => {
  const out = mapVerifierInfo(clone(), ACCEPTED_CREDENTIALS);
  if (out.state !== "success") throw new Error("fixture should map to success");
  const base = {
    x5c: out.x5c,
    rootPem: readFileSync(rootCa, "utf8"),
    expectedHost: "issuer.localtest.me",
    expectedIssuer: ISSUER,
    issuer: out.issuer,
    verifiedKey: out.issuerPublicKey,
  };

  it("accepts the recorded issuer chain", () => {
    expect(checkIssuerTrust(base)).toEqual({ ok: true });
  });

  it("rejects an expired chain", () => {
    expect(checkIssuerTrust({ ...base, now: new Date("2031-01-01") }).ok).toBe(false);
  });

  it("rejects another issuer, another host and a mismatched key", () => {
    expect(checkIssuerTrust({ ...base, issuer: "https://evil.example" }).ok).toBe(false);
    expect(checkIssuerTrust({ ...base, expectedHost: "tickets.localtest.me" }).ok).toBe(false);
    expect(checkIssuerTrust({ ...base, verifiedKey: { ...base.verifiedKey, x: "AAAA" } }).ok).toBe(false);
    expect(checkIssuerTrust({ ...base, verifiedKey: undefined }).ok).toBe(false);
  });
});
