import { AGENT_VCT } from "@/lib/config";

// SD-JWT VC type metadata for the agent credential's vct, served at
// https://registry.localtest.me/credentials/agent/v1 (the vct value itself).
export function GET() {
  return Response.json(
    {
      vct: AGENT_VCT,
      name: "Agent credential",
      description: "An AI agent accredited by the Agent Registry to act for its operator.",
      display: [{ locale: "en", name: "Agent credential", description: "Accredited personal agent" }],
      claims: [
        { path: ["agent_id"], sd: "never", display: [{ locale: "en", label: "Agent ID" }] },
        { path: ["agent_name"], sd: "always", display: [{ locale: "en", label: "Agent name" }] },
        { path: ["provider"], sd: "always", display: [{ locale: "en", label: "Provider" }] },
        { path: ["operator"], sd: "always", display: [{ locale: "en", label: "Operator" }] },
        { path: ["accreditation_level"], sd: "always", display: [{ locale: "en", label: "Accreditation level" }] },
      ],
    },
    { headers: { "cache-control": "public, max-age=300" } },
  );
}
