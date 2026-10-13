import "server-only";
import { cookies } from "next/headers";
import { readDb, type Account, type LanternSession } from "./store";

// Lantern Tickets browser state: a signed-in session cookie (random id -> lanternSessions) and a
// per-checkout cookie that binds a wallet sign-in to the browser that started the checkout.
export const SESSION_COOKIE = "lantern_session";
export const checkoutCookie = (checkoutId: string) => `lantern_chk_${checkoutId}`;

export function cookieOptions(secure: boolean, maxAgeSeconds: number) {
  return { httpOnly: true, sameSite: "lax" as const, secure, path: "/", maxAge: maxAgeSeconds };
}

export async function currentLanternSession(): Promise<{ session: LanternSession; account: Account } | null> {
  const sid = (await cookies()).get(SESSION_COOKIE)?.value;
  if (!sid) return null;
  const db = readDb();
  const session = db.lanternSessions[sid];
  const account = session ? db.accounts[session.accountId] : undefined;
  return session && account ? { session, account } : null;
}
