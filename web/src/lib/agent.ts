// The agent credential's claims (docs/WIRING.md, "The agent credential").

export const PROVIDERS = ["OpenClaw", "Other"] as const;
export type Provider = (typeof PROVIDERS)[number];

export const ACCREDITATION_LEVELS = ["basic", "verified"] as const;
export type AccreditationLevel = (typeof ACCREDITATION_LEVELS)[number];

export type AgentClaims = {
  agent_id: string;
  agent_name: string;
  provider: string;
  operator: string;
  accreditation_level: AccreditationLevel;
};

export const AGENT_ID_PATTERN = /^agt_[0-9A-Za-z]{6}$/;

export const isProvider = (v: unknown): v is Provider =>
  typeof v === "string" && (PROVIDERS as readonly string[]).includes(v);

export const isAccreditationLevel = (v: unknown): v is AccreditationLevel =>
  typeof v === "string" && (ACCREDITATION_LEVELS as readonly string[]).includes(v);

/** "Seats held for {agent_name} ({provider}, operated by {operator}, {accreditation_level})" */
export function describeAgent(c: AgentClaims): string {
  return `${c.agent_name} (${c.provider}, operated by ${c.operator}, ${c.accreditation_level})`;
}
