import { currentLanternSession } from "@/lib/lanternSession";
import { linker } from "@/lib/request";
import { AgentChipIcon, LanternMark } from "./icons";

export type Step = "seats" | "signin" | "confirm" | "done";
const STEPS: { id: Step; label: string }[] = [
  { id: "seats", label: "Seats" },
  { id: "signin", label: "Sign in" },
  { id: "confirm", label: "Confirm" },
  { id: "done", label: "Done" },
];

/** Lantern header: brand, then either the event nav or the checkout steps, plus who is signed in. */
export async function LanternHeader({ step }: { step?: Step }) {
  const to = await linker("tickets");
  const signedIn = await currentLanternSession();
  return (
    <header className="lan-header">
      <div className="lan-header-inner">
        <a className="lan-brand" href={to("/")}>
          <LanternMark />
          <span>Lantern Tickets</span>
        </a>
        <div className="lan-header-right">
          {step ? (
            <ol className="lan-steps" aria-label="Checkout steps">
              {STEPS.map((s) => (
                <li key={s.id} aria-current={s.id === step ? "step" : undefined}>
                  {s.label}
                </li>
              ))}
            </ol>
          ) : (
            <nav className="lan-nav" aria-label="Lantern">
              <a href={to("/")}>This week</a>
              <span>Venues</span>
            </nav>
          )}
          {signedIn && (
            <span className="lan-chip" id="signed-in-as">
              <span className="lan-chip-icon">
                <AgentChipIcon />
              </span>
              <span>
                <span className="visually-hidden">Signed in as </span>
                {signedIn.account.displayName}
              </span>
            </span>
          )}
        </div>
      </div>
    </header>
  );
}
