import { describe, expect, it } from "vitest";
import { AGENT_CREDENTIAL, buildSignInDcql, type AcceptedCredential } from "./credentialTypes";

describe("buildSignInDcql", () => {
  it("builds the agent query with one required credential set", () => {
    expect(buildSignInDcql()).toEqual({
      credentials: [
        {
          id: "agent",
          format: "dc+sd-jwt",
          meta: { vct_values: ["https://registry.localtest.me/credentials/agent/v1"] },
          claims: [
            { path: ["agent_id"] },
            { path: ["agent_name"] },
            { path: ["provider"] },
            { path: ["operator"] },
            { path: ["accreditation_level"] },
          ],
        },
      ],
      credential_sets: [{ options: [["agent"]], required: true }],
    });
  });

  it("adds a further credential as an alternative option, not a rewrite", () => {
    const person: AcceptedCredential = {
      ...AGENT_CREDENTIAL,
      id: "person",
      kind: "person",
      vct: "https://example.test/person",
      claims: ["given_name", "family_name"],
    };
    const dcql = buildSignInDcql([AGENT_CREDENTIAL, person]);
    expect(dcql.credentials.map((c) => c.id)).toEqual(["agent", "person"]);
    expect(dcql.credential_sets).toEqual([{ options: [["agent"], ["person"]], required: true }]);
  });

  it("keys agent accounts by agent_id", () => {
    expect(AGENT_CREDENTIAL.accountKey({ agent_id: "agt_7Hk2pQ" })).toBe("agent:agt_7Hk2pQ");
  });
});
