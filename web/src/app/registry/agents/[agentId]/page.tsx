import type { Metadata } from "next";
import { notFound } from "next/navigation";
import { CopyButton } from "@/components/CopyButton";
import { formatRemaining } from "@/lib/time";
import { CredentialCard } from "@/components/CredentialCard";
import { BackIcon, CheckIcon } from "@/components/icons";
import { OfferWatcher } from "@/components/OfferWatcher";
import { lastIssued, latestOffer, refreshOffer, validUntil } from "@/lib/registry";
import { linker, qrSvg } from "@/lib/request";
import { readDb } from "@/lib/store";
import { issueCredentialAction } from "../../actions";

export const metadata: Metadata = { title: "Agent" };

const cap = (s: string) => s.charAt(0).toUpperCase() + s.slice(1);
const dateLong = (d: Date) => d.toLocaleDateString("en-GB", { day: "numeric", month: "short", year: "numeric" });
const dateTime = (iso: string) =>
  `${dateLong(new Date(iso))}, ${new Date(iso).toLocaleTimeString("en-GB", { hour: "2-digit", minute: "2-digit" })}`;

function collectedWhen(issuedAt: string) {
  return Date.now() - new Date(issuedAt).getTime() < 5 * 60_000 ? "a moment ago" : `on ${dateTime(issuedAt)}`;
}

function remaining(expiresAt: number) {
  return { left: expiresAt - Date.now(), text: formatRemaining(expiresAt - Date.now()) };
}

export default async function AgentPage({
  params,
  searchParams,
}: {
  params: Promise<{ agentId: string }>;
  searchParams: Promise<{ error?: string }>;
}) {
  const to = await linker("registry");
  const { agentId } = await params;
  const { error } = await searchParams;

  const db = readDb();
  const agent = db.agents[agentId];
  if (!agent) notFound();

  // Latest offer, refreshed from issuer2 (persists "issued" the first time it is seen).
  let offer = latestOffer(agentId, db);
  let offerStatus = null;
  if (offer) ({ status: offerStatus, offer } = await refreshOffer(offer));
  const issued = offer?.issuedAt ? offer : lastIssued(agentId, readDb());
  const { left, text: initialRemaining } = offer ? remaining(offer.expiresAt) : { left: 0, text: "0:00" };
  const offerActive = !!offer && !offer.issuedAt && left > 0 && offerStatus !== "failed" && offerStatus !== "expired";
  const qr = offerActive ? await qrSvg(offer!.credentialOffer, { dark: "#0E1116", light: "#FFFFFF" }) : null;
  const level = cap(agent.accreditationLevel);

  return (
    <main className="reg-main detail">
      <div className="reg-titleblock">
        <a className="back-link" href={to("/")}>
          <BackIcon />
          Your agents
        </a>
        <h1 className="reg-h1 agent">{agent.name}</h1>
        <p className="reg-sub">
          {agent.provider} · {level} · operated by {agent.operatorName}
        </p>
      </div>

      <div className="reg-grid detail">
        <section aria-label="Agent credential" className="cred-section">
          <CredentialCard
            id="credential-card"
            issued={!!issued}
            name={agent.name}
            provider={agent.provider}
            operator={agent.operatorName}
            level={agent.accreditationLevel}
            agentId={agent.agentId}
            foot={issued ? `Issued by Agent Registry · valid until ${dateLong(validUntil(issued.issuedAt!))}` : undefined}
          />
          <p className="cred-caption">
            Websites always see the agent ID. Your agent&apos;s wallet decides whether to show the name, runtime,
            operator and accreditation.
          </p>
        </section>

        {offerActive ? (
          <section aria-labelledby="offer-title" className="reg-panel" key={`offer-${offer!.offerId}`}>
            <div className="offer-head">
              <h2 id="offer-title" className="reg-h2">
                Send this credential to your agent
              </h2>
              <p>Paste the link into your chat with the agent. Your agent&apos;s wallet collects the credential. The link works once.</p>
            </div>

            <div className="offer-row">
              <figure
                className="qr-reg"
                role="img"
                aria-label="QR code for the credential offer"
                dangerouslySetInnerHTML={{ __html: qr! }}
              />
              <OfferWatcher
                statusUrl={`/api/registry/offers/${encodeURIComponent(offer!.offerId)}`}
                expiresAt={offer!.expiresAt}
                initialRemaining={initialRemaining}
              />
            </div>

            <div className="reg-field">
              <span className="reg-legend" id="offer-link-label">
                Credential offer link
              </span>
              <div className="link-field">
                <a
                  id="credential-offer-link"
                  className="link-box"
                  href={offer!.credentialOffer}
                  aria-describedby="offer-link-label"
                >
                  <span>{offer!.credentialOffer}</span>
                </a>
                <CopyButton text={offer!.credentialOffer} id="copy-offer-link" />
              </div>
            </div>

            <ol className="steps3">
              <li>
                <span className="num">01</span>
                <span>Copy the link</span>
              </li>
              <li>
                <span className="num">02</span>
                <span>Send it to your agent</span>
              </li>
              <li>
                <span className="num">03</span>
                <span>Your agent&apos;s wallet collects the credential</span>
              </li>
            </ol>
          </section>
        ) : issued ? (
          <section aria-labelledby="issued-title" className="reg-panel fade-in" id="credential-issued">
            <span className="success-dot">
              <CheckIcon size={28} color="#0B6B49" width={2.5} />
            </span>
            <div className="issued-head">
              <h2 id="issued-title">Your agent holds its credential</h2>
              <p>
                {agent.name} collected it {collectedWhen(issued.issuedAt!)}. The offer link no longer works.
              </p>
            </div>
            <dl className="reg-facts">
              <dt>Issued</dt>
              <dd>{dateTime(issued.issuedAt!)}</dd>
              <dt>Valid until</dt>
              <dd>{dateLong(validUntil(issued.issuedAt!))}</dd>
              <dt>Bound to</dt>
              <dd>A key only the agent&apos;s wallet holds</dd>
            </dl>
            <form action={issueCredentialAction.bind(null, agent.agentId)}>
              <button type="submit" className="reg-btn secondary" id="issue-credential">
                Issue a new offer
              </button>
            </form>
          </section>
        ) : (
          <section aria-labelledby="offer-title" className="reg-panel">
            <div className="offer-head">
              <h2 id="offer-title" className="reg-h2">
                Send this credential to your agent
              </h2>
              <p>
                {offer
                  ? "The last offer expired before your agent's wallet collected it. Create a new one."
                  : "Create a one-time offer link. Paste it into your chat with the agent, and your agent's wallet collects the credential."}
              </p>
            </div>
            {error === "issuer" && (
              <p className="reg-error" role="alert">
                The issuer could not create an offer. Try again in a moment.
              </p>
            )}
            <form action={issueCredentialAction.bind(null, agent.agentId)}>
              <button type="submit" className="reg-btn" id="issue-credential">
                Issue agent credential
              </button>
            </form>
          </section>
        )}
      </div>
    </main>
  );
}
