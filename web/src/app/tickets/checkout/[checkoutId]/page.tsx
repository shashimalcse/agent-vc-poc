import type { Metadata } from "next";
import { notFound, redirect } from "next/navigation";
import { BackIcon, WalletIcon } from "@/components/icons";
import { LanternHeader } from "@/components/LanternHeader";
import { OrderAside } from "@/components/OrderAside";
import { currentLanternSession } from "@/lib/lanternSession";
import { linker } from "@/lib/request";
import { readDb } from "@/lib/store";
import { seatList } from "@/lib/tickets";
import { continueWithWalletAction } from "../../actions";

export const metadata: Metadata = { title: "Sign in" };

// Step 2: one door for everyone. People and agents both sign in with a wallet credential.
export default async function SignInPage({
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
  if (await currentLanternSession()) redirect(to(`/checkout/${checkoutId}/confirm`));

  return (
    <>
      <LanternHeader step="signin" />
      <main className="lan-main split">
        <div className="lan-col">
          <div className="lan-intro">
            <a className="back-link" href={to("/")}>
              <BackIcon />
              Change seats
            </a>
            <h1 className="lan-h1">
              Sign in to hold
              <br />
              your seats
            </h1>
            <p className="lan-lede">
              {seatList(checkout.seats)} {checkout.seats.length === 1 ? "is" : "are"} reserved for you for 10 minutes.
              New here? Signing in creates your account.
            </p>
          </div>

          {error === "verifier" && (
            <p className="lan-error" role="alert">
              We couldn&apos;t start a wallet sign-in. Try again in a moment.
            </p>
          )}

          <section aria-labelledby="wallet-signin-title" className="lan-card signin">
            <div className="signin-title">
              <span className="signin-icon">
                <WalletIcon />
              </span>
              <h2 id="wallet-signin-title">Sign in with your wallet</h2>
            </div>
            <p className="lan-p">
              Share a digital credential from your wallet. Yours, or your personal agent&apos;s. No password, no
              CAPTCHA.
            </p>
            <form action={continueWithWalletAction.bind(null, checkout.id)}>
              <button type="submit" className="lan-btn" id="continue-with-wallet">
                Continue with wallet
              </button>
            </form>
          </section>

          <p className="lan-small">
            No wallet?{" "}
            <a href={to(`/checkout/${checkout.id}/email`)} id="email-signin-link">
              Use email instead
            </a>
          </p>
        </div>

        <OrderAside seats={checkout.seats} />
      </main>
    </>
  );
}
