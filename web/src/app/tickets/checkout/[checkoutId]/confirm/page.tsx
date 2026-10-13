import type { Metadata } from "next";
import { notFound, redirect } from "next/navigation";
import { CheckIcon } from "@/components/icons";
import { LanternHeader } from "@/components/LanternHeader";
import { SeatChips } from "@/components/OrderAside";
import { acceptedCredential } from "@/lib/credentialTypes";
import { currentLanternSession } from "@/lib/lanternSession";
import { linker } from "@/lib/request";
import { readDb } from "@/lib/store";
import { EVENT, MAX_SEATS, checkAccountCap } from "@/lib/tickets";
import { holdSeatsAction } from "../../../actions";

export const metadata: Metadata = { title: "Confirm" };

const cap = (s: string) => s.charAt(0).toUpperCase() + s.slice(1);

// Step 3: signed in. Welcome the account and confirm the order.
export default async function ConfirmPage({
  params,
  searchParams,
}: {
  params: Promise<{ checkoutId: string }>;
  searchParams: Promise<{ error?: string }>;
}) {
  const to = await linker("tickets");
  const { checkoutId } = await params;
  const { error } = await searchParams;
  const db = readDb();
  const checkout = db.checkouts[checkoutId];
  if (!checkout) notFound();
  if (checkout.status === "held" && checkout.orderId) redirect(to(`/orders/${checkout.orderId}`));
  const signedIn = await currentLanternSession();
  if (!signedIn) redirect(to(`/checkout/${checkoutId}`));

  const { account, session } = signedIn;
  const credential = acceptedCredential(account.credentialId);
  const c = account.claims;
  const isAgent = account.kind === "agent";
  const held = checkAccountCap(Object.values(db.orders), account.id, checkout.eventId, 0).alreadyHeld;
  const n = checkout.seats.length;

  return (
    <>
      <LanternHeader step="confirm" />
      <main className="lan-main">
        <div className="lan-intro">
          <p className="signed-in-line" id="signed-in-status">
            <CheckIcon size={18} color="#FFB547" width={2.4} />
            {session.newAccount
              ? `Signed in with wallet · account created from ${credential?.label ?? "a credential"}`
              : "Signed in with wallet"}
          </p>
          <h1 className="lan-h1 welcome" id="welcome-heading">
            Welcome,
            <br />
            {account.displayName}
          </h1>
          <p className="lan-lede">
            {isAgent
              ? `Your Lantern account belongs to this agent, operated by ${c.operator}. Next time it signs in the same way.`
              : "Your Lantern account belongs to the holder of this credential. Next time you sign in the same way."}
          </p>
        </div>

        <div className="lan-row stretch">
          <section aria-labelledby="account-title" className="lan-card account">
            <h2 id="account-title" className="lan-h2 small">
              Your account
            </h2>
            {isAgent ? (
              <dl className="lan-dl account" id="account-details">
                <dt>Agent</dt>
                <dd>{c.agent_name}</dd>
                <dt>Agent ID</dt>
                <dd className="mono" id="account-agent-id">
                  {c.agent_id}
                </dd>
                <dt>Runs on</dt>
                <dd>{c.provider}</dd>
                <dt>Operator</dt>
                <dd>{c.operator}</dd>
                <dt>Accredited by</dt>
                <dd>
                  {credential?.issuerName ?? account.issuer} · {cap(c.accreditation_level)}
                </dd>
              </dl>
            ) : (
              <dl className="lan-dl account" id="account-details">
                {Object.entries(c).map(([k, v]) => (
                  <div key={k} style={{ display: "contents" }}>
                    <dt>{k}</dt>
                    <dd>{v}</dd>
                  </div>
                ))}
              </dl>
            )}
          </section>

          <section aria-labelledby="confirm-title" className="lan-card confirm">
            <h2 id="confirm-title" className="lan-h2 small">
              Confirm your order
            </h2>
            <SeatChips seats={checkout.seats} id="confirm-seats" />
            <dl className="lan-dl">
              <dt>{EVENT.title}</dt>
              <dd>
                {n} ticket{n === 1 ? "" : "s"}
              </dd>
              <dt>Limit per {isAgent ? "agent" : "account"}</dt>
              <dd>{MAX_SEATS} tickets</dd>
              {held > 0 && (
                <>
                  <dt>Already held</dt>
                  <dd>{held}</dd>
                </>
              )}
            </dl>
            {error && (
              <p className="lan-error" role="alert" id="hold-error">
                {error}
              </p>
            )}
            <form action={holdSeatsAction.bind(null, checkout.id)} style={{ marginTop: "auto" }}>
              <button type="submit" className="lan-btn" id="hold-seats">
                Hold my seats
              </button>
            </form>
          </section>
        </div>
        {error && (
          <p className="lan-small">
            <a href={to("/")}>Change seats</a>
          </p>
        )}
      </main>
    </>
  );
}
