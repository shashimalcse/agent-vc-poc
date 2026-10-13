"use server";

import { cookies } from "next/headers";
import { redirect } from "next/navigation";
import { createCheckout, holdSeats, startWalletSignIn } from "@/lib/checkout";
import { checkoutCookie, cookieOptions, currentLanternSession } from "@/lib/lanternSession";
import { isHttps, linker } from "@/lib/request";

export async function startCheckoutAction(formData: FormData) {
  const to = await linker("tickets");
  const result = createCheckout(formData.getAll("seat").map(String));
  if (!result.ok) redirect(`${to("/")}?error=${encodeURIComponent(result.error)}#seats-title`);

  const { checkout } = result;
  // Binds a later wallet sign-in to this browser (see consumeVerification).
  (await cookies()).set(checkoutCookie(checkout.id), checkout.browserSecret!, cookieOptions(await isHttps(), 60 * 60));

  // Already signed in: skip straight to Confirm.
  if (await currentLanternSession()) redirect(to(`/checkout/${checkout.id}/confirm`));
  redirect(to(`/checkout/${checkout.id}`));
}

export async function continueWithWalletAction(checkoutId: string) {
  const to = await linker("tickets");
  try {
    await startWalletSignIn(checkoutId);
  } catch (err) {
    console.error(err);
    redirect(`${to(`/checkout/${checkoutId}`)}?error=verifier`);
  }
  redirect(to(`/checkout/${checkoutId}/verify`));
}

export async function holdSeatsAction(checkoutId: string) {
  const to = await linker("tickets");
  const signedIn = await currentLanternSession();
  if (!signedIn) redirect(to(`/checkout/${checkoutId}`));
  const result = holdSeats(checkoutId, signedIn.account.id);
  if (!result.ok) redirect(`${to(`/checkout/${checkoutId}/confirm`)}?error=${encodeURIComponent(result.error)}`);
  redirect(to(`/orders/${result.orderId}`));
}

