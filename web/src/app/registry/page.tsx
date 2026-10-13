import { AgentIcon, CheckIcon } from "@/components/icons";
import { ACCREDITATION_LEVELS, PROVIDERS } from "@/lib/agent";
import { agentCredentialState } from "@/lib/registry";
import { linker } from "@/lib/request";
import { readDb } from "@/lib/store";
import { registerAgentAction } from "./actions";

const cap = (s: string) => s.charAt(0).toUpperCase() + s.slice(1);

export default async function RegistryHome({ searchParams }: { searchParams: Promise<{ error?: string }> }) {
  const to = await linker("registry");
  const { error } = await searchParams;
  const db = readDb();
  const agents = Object.values(db.agents).sort((a, b) => b.createdAt.localeCompare(a.createdAt));

  return (
    <main className="reg-main">
      <div className="reg-hero">
        <p className="reg-eyebrow">For personal agents</p>
        <h1 className="reg-h1">Give your agent a credential websites can trust.</h1>
        <p className="reg-lede">
          Register the agent that works for you. Your agent&apos;s wallet holds a signed credential that says who runs it, so
          sites can let it in without passwords or CAPTCHAs.
        </p>
      </div>

      <div className="reg-grid">
        <section aria-labelledby="register-title" className="reg-panel">
          <h2 id="register-title" className="reg-h2">
            Register an agent
          </h2>
          {error === "invalid" && (
            <p className="reg-error" role="alert">
              Give the agent a name of up to 60 characters.
            </p>
          )}
          <form action={registerAgentAction} id="register-agent-form" style={{ display: "contents" }}>
            <div className="reg-field">
              <label htmlFor="agent-name">Agent name</label>
              <input id="agent-name" name="name" type="text" required maxLength={60} placeholder="Ada's OpenClaw" />
            </div>
            <div className="reg-field">
              <label htmlFor="agent-provider">Runs on</label>
              <select id="agent-provider" name="provider" defaultValue="OpenClaw">
                {PROVIDERS.map((p) => (
                  <option key={p} value={p}>
                    {p}
                  </option>
                ))}
              </select>
            </div>
            <fieldset className="reg-fieldset">
              <legend className="reg-legend">Accreditation</legend>
              <div className="level-options">
                {ACCREDITATION_LEVELS.map((l) => (
                  <label className="level-option" key={l}>
                    <input type="radio" name="accreditationLevel" value={l} defaultChecked={l === "verified"} />
                    {cap(l)}
                  </label>
                ))}
              </div>
            </fieldset>
            <button type="submit" className="reg-btn" id="register-agent">
              Register agent
            </button>
          </form>
        </section>

        <section aria-labelledby="agents-title" className="reg-agents">
          <div className="reg-agents-head">
            <h2 id="agents-title" className="reg-h2">
              Your agents
            </h2>
            <span className="reg-count">{agents.length} registered</span>
          </div>

          {agents.length === 0 && <p className="reg-empty">No agents yet. Register the agent that works for you.</p>}

          {agents.map((a) => {
            const state = agentCredentialState(a.agentId, db);
            const issued = state === "issued";
            const waiting = state === "waiting";
            return (
              <a key={a.agentId} href={to(`/agents/${a.agentId}`)} className="agent-row" id={`agent-${a.agentId}`}>
                <span className={`agent-icon${issued ? " solid" : ""}`}>
                  <AgentIcon color={issued ? "#FFFFFF" : "#2443E6"} />
                </span>
                <span className="agent-meta">
                  <span className="agent-name">{a.name}</span>
                  <span className="agent-sub">
                    {a.provider} · {cap(a.accreditationLevel)} · <span className="mono">{a.agentId}</span>
                  </span>
                </span>
                {issued ? (
                  <span className="reg-pill ok">
                    <CheckIcon color="#0B6B49" />
                    Credential issued
                  </span>
                ) : waiting ? (
                  <span className="reg-pill wait">Offer waiting</span>
                ) : (
                  <span className="reg-pill">No credential yet</span>
                )}
              </a>
            );
          })}

          <p className="reg-note">
            Each agent gets its own credential. Your agent&apos;s wallet keeps it; this registry never sees the
            agent&apos;s keys.
          </p>
        </section>
      </div>
    </main>
  );
}
