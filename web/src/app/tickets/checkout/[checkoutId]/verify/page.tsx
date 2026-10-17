import type { Metadata } from "next";
import { notFound, redirect } from "next/navigation";
import { formatRemaining } from "@/lib/time";
import { BackIcon, CheckIcon, OpenWalletIcon } from "@/components/icons";
import { LanternHeader } from "@/components/LanternHeader";
import { SignInWatcher } from "@/components/SignInWatcher";
import { advanceVerification } from "@/lib/checkout";
import { linker, qrSvg } from "@/lib/request";
import { readDb } from "@/lib/store";
import { seatList } from "@/lib/tickets";
import { continueWithWalletAction } from "../../../actions";

export const metadata: Metadata = { title: "Sign in with your wallet" };

const CHECKS = [
  "An issuer we trust signed the credential",
  "Only the holder's own wallet could present it",
  "Who you are: your name, or for an agent, who runs it",
];

function remaining(expiresAt: number) {
  return formatRemaining(expiresAt - Date.now());
}

// Step 2b: the wallet sign-in request. Server-rendered QR code and <a id="verify-agent-link">.
export default async function VerifyPage({
  params,
  searchParams,
}: {
  params: Promise<{ checkoutId: string }>;
  searchParams: Promise<{ error?: string }>;
}) {
  const to = await linker("tickets");
  const { checkoutId } = await params;
  const { error } = await searchParams;
  const checkout = readDb().checkouts[checkoutId];
  if (!checkout) notFound();
  if (checkout.status === "held" && checkout.orderId) redirect(to(`/orders/${checkout.orderId}`));
  if (!checkout.verificationId) redirect(to(`/checkout/${checkoutId}`));

  const v = await advanceVerification(checkout.verificationId);
  if (!v) redirect(to(`/checkout/${checkoutId}`));
  // A verified sign-in is finished only by the wallet's continuation (the success redirect with its
  // response_code), never by this page: otherwise a page that relayed someone else's request could
  // be signed in by polling.
  const answered = v.status === "verified" && !error;
  const pending = v.status === "pending";
  const qr = pending ? await qrSvg(v.requestUrl, { dark: "#131117", light: "#F4EFE8" }) : null;
  const message = error ?? v.message ?? "The wallet sign-in did not complete.";

  return (
    <>
      <LanternHeader step="signin" />
      <main className="lan-main split">
        <div className="lan-col verify">
          <a className="back-link" href={to(`/checkout/${checkoutId}`)}>
            <BackIcon />
            Back
          </a>
          <h1 className="lan-h1">
            Sign in with
            <br />
            your wallet
          </h1>
          <p className="lan-lede">
            Open this sign-in request in your wallet, or hand it to your agent&apos;s wallet. First time here? We
            create your account from the credential.
          </p>

          {answered ? (
            <div role="status" className="status-box done" id="verification-answered">
              <span className="status-dot" aria-hidden="true" />
              <span>Your wallet has answered. Continue with the link it returned.</span>
            </div>
          ) : pending ? (
            <>
              <a id="verify-agent-link" className="wallet-link" href={v.requestUrl}>
                <OpenWalletIcon />
                Open in wallet
              </a>
              <SignInWatcher verificationId={v.id} expiresAt={v.expiresAt} initialRemaining={remaining(v.expiresAt)} />
              <p className="lan-small">
                <a href={to(`/checkout/${checkoutId}/verify`)} id="check-status-link">
                  Check status
                </a>
              </p>
            </>
          ) : (
            <>
              <div role="alert" className="status-box error" id="verification-error">
                <span className="status-dot" aria-hidden="true" />
                <span>{message}</span>
              </div>
              <form action={continueWithWalletAction.bind(null, checkoutId)}>
                <button type="submit" className="lan-btn" style={{ width: "auto" }} id="retry-wallet-signin">
                  Try again
                </button>
              </form>
            </>
          )}

          <section aria-labelledby="check-title" style={{ display: "flex", flexDirection: "column", gap: 16 }}>
            <h2 id="check-title" className="lan-h2 small">
              What we check before signing you in
            </h2>
            <ul className="check-list">
              {CHECKS.map((c) => (
                <li key={c}>
                  <CheckIcon size={20} color="#FFB547" width={2.4} />
                  {c}
                </li>
              ))}
            </ul>
          </section>
        </div>

        <aside aria-label="Scan with a wallet" className="lan-card scan">
          {qr ? (
            <figure
              className="qr-lan"
              role="img"
              aria-label="QR code for the wallet sign-in request"
              dangerouslySetInnerHTML={{ __html: qr }}
            />
          ) : (
            <div className="qr-lan" aria-hidden="true" style={{ opacity: 0.15 }} />
          )}
          <p className="scan-caption">Scan with your phone&apos;s wallet, or let your agent pass the link to its wallet.</p>
          <div className="scan-foot">
            <span>Seats {seatList(checkout.seats).replace(" and ", ", ")}</span>
            <span>
              {checkout.seats.length} ticket{checkout.seats.length === 1 ? "" : "s"}
            </span>
          </div>
        </aside>
      </main>
    </>
  );
}
