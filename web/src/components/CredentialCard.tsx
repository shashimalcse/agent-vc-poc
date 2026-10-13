import { RegistryMark } from "./icons";

const cap = (s: string) => s.charAt(0).toUpperCase() + s.slice(1);

// The one credential card (design: Registry offer/issued, Lantern "Placed by").
// Dashed and muted while not collected; solid trust blue once issued. The class change
// animates (see .cred-card in globals.css).
export function CredentialCard({
  issued,
  name,
  provider,
  operator,
  level,
  agentId,
  label = "AGENT CREDENTIAL",
  foot,
  compact = false,
  id,
}: {
  issued: boolean;
  name: string;
  provider: string;
  operator: string;
  level: string;
  agentId: string;
  label?: string;
  foot?: string;
  compact?: boolean;
  id?: string;
}) {
  return (
    <div className={`cred-card${issued ? " issued" : ""}${compact ? " compact" : ""}`} id={id}>
      <div className="cred-top">
        <span className="cred-label">{label}</span>
        {issued ? <RegistryMark inverse /> : <span className="cred-pending">Not collected yet</span>}
      </div>
      <div className="cred-name">{name}</div>
      <dl className="cred-grid">
        <div>
          <dt className="cred-k">Runs on</dt>
          <dd className="cred-v">{provider}</dd>
        </div>
        <div>
          <dt className="cred-k">Operator</dt>
          <dd className="cred-v">{operator}</dd>
        </div>
        <div>
          <dt className="cred-k">Accreditation</dt>
          <dd className="cred-v">{cap(level)}</dd>
        </div>
        <div>
          <dt className="cred-k">Agent ID</dt>
          <dd className="cred-v mono">{agentId}</dd>
        </div>
      </dl>
      {issued && foot && <div className="cred-foot">{foot}</div>}
    </div>
  );
}
