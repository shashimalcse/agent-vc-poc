import type { Metadata } from "next";
import { notFound } from "next/navigation";
import { CredentialCard } from "@/components/CredentialCard";
import { CheckIcon } from "@/components/icons";
import { LanternHeader } from "@/components/LanternHeader";
import { acceptedCredential } from "@/lib/credentialTypes";
import { linker } from "@/lib/request";
import { readDb } from "@/lib/store";
import { EVENT, MAX_SEATS, checkAccountCap, countWord, currentEventDate, shortDate } from "@/lib/tickets";

export const metadata: Metadata = { title: "Seats held" };

const time = (iso: string) => new Date(iso).toLocaleTimeString("en-GB", { hour: "2-digit", minute: "2-digit" });

// Step 4: Done. Ticket stubs and the credential the account signed in with.
export default async function OrderPage({ params }: { params: Promise<{ orderId: string }> }) {
  const to = await linker("tickets");
  const { orderId } = await params;
  const db = readDb();
  const order = db.orders[orderId];
  if (!order) notFound();

  const account = order.accountId ? db.accounts[order.accountId] : undefined;
  const credential = account ? acceptedCredential(account.credentialId) : undefined;
  const c = order.claims;
  const isAgent = order.kind === "agent" && !!c;
  const holding = order.accountId
    ? checkAccountCap(Object.values(db.orders), order.accountId, order.eventId, 0).alreadyHeld
    : order.seats.length;
  const n = order.seats.length;
  const stubDate = `${shortDate(currentEventDate())} · ${EVENT.show}`;

  return (
    <>
      <LanternHeader step="done" />
      <main className="lan-main held">
        <div className="lan-intro held">
          <span className="done-dot">
            <CheckIcon size={32} color="#131117" width={2.6} />
          </span>
          <h1 className="lan-h1 held" id="order-heading">
            Seats held.
          </h1>
          <p className="lan-lede wide" id="order-summary">
            {countWord(n)} seat{n === 1 ? "" : "s"} for {EVENT.title},{" "}
            {isAgent ? "held on the account of an accredited agent." : "held on your account."}
          </p>
        </div>

        <div className="lan-row stretch">
          <section aria-labelledby="tickets-title" className="tickets-col">
            <h2 id="tickets-title" className="lan-h2 small muted">
              Your tickets
            </h2>
            <div className="stubs" id="order-seats">
              {order.seats.map((s) => (
                <div className="stub" key={s}>
                  <span className="stub-date">{stubDate}</span>
                  <span className="stub-seat">{s}</span>
                  <span className="stub-row">
                    Row {s[0]} · Seat {s.slice(1)}
                  </span>
                </div>
              ))}
            </div>
            <p className="lan-small" id="order-cap">
              This {isAgent ? "agent" : "account"} now holds {holding} of the {MAX_SEATS} tickets allowed per{" "}
              {isAgent ? "agent" : "account"} for this show.
            </p>
          </section>

          {isAgent && (
            <section aria-labelledby="agent-title" className="placed-col">
              <h2 id="agent-title" className="lan-h2 small muted">
                Placed by
              </h2>
              <CredentialCard
                id="placed-by"
                issued
                compact
                label={`AGENT CREDENTIAL · ${c.accreditation_level.toUpperCase()}`}
                name={c.agent_name}
                provider={c.provider}
                operator={c.operator}
                level={c.accreditation_level}
                agentId={c.agent_id}
                foot={`Issued by ${credential?.issuerName ?? "Agent Registry"} · used to sign in to Lantern Tickets at ${time(order.signedInAt ?? order.createdAt)}`}
              />
            </section>
          )}
        </div>

        <div className="held-foot">
          <span className="order-id" id="order-id">
            Order {order.id}
          </span>
          <a className="lan-btn outline" href={to("/")}>
            Back to the event
          </a>
        </div>
      </main>
    </>
  );
}
