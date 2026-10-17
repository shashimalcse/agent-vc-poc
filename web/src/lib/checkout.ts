import "server-only";
import { timingSafeEqual } from "node:crypto";
import { readFileSync } from "node:fs";
import { config } from "./config";
import { ACCEPTED_CREDENTIALS } from "./credentialTypes";
import { checkIssuerTrust } from "./issuerTrust";
import { mutate, newId, randomId, readDb, type Account, type Checkout, type Verification } from "./store";
import { EVENT, checkAccountCap, seatOrder, takenSeats, validateSelection } from "./tickets";
import { mapVerifierInfo } from "./verifierResponse";
import { createSignInSession, getVerificationInfo } from "./verifier2";

// Lantern checkout:
//   seats -> checkout(open, seats reserved 10 min)
//   -> wallet sign-in: verification(pending) --verifier2 SUCCESSFUL + claims + issuer trust-->
//      verification(verified, accountId)  [account created or found by credential key]
//   -> browser session (single use of the verification, bound to the checkout cookie)
//   -> confirm -> hold seats (cap 4 per account) -> order, checkout(held)

export function takenFor(db = readDb(), exceptCheckoutId?: string): Set<string> {
  return takenSeats(Object.values(db.orders), EVENT.id, Object.values(db.checkouts), Date.now(), exceptCheckoutId);
}

export function createCheckout(rawSeats: string[]): { ok: true; checkout: Checkout } | { ok: false; error: string } {
  return mutate((db) => {
    const selection = validateSelection(rawSeats, takenFor(db));
    if (!selection.ok) return selection;
    const checkout: Checkout = {
      id: newId("chk"),
      eventId: EVENT.id,
      seats: selection.seats,
      status: "open",
      reservedUntil: Date.now() + config.seatHoldSeconds * 1000,
      browserSecret: randomId(32),
      createdAt: new Date().toISOString(),
    };
    db.checkouts[checkout.id] = checkout;
    return { ok: true as const, checkout };
  });
}

export async function startWalletSignIn(checkoutId: string): Promise<Verification> {
  const checkout = readDb().checkouts[checkoutId];
  if (!checkout) throw new Error("No such checkout");
  if (checkout.status !== "open") throw new Error("Checkout is already complete");

  const session = await createSignInSession(checkoutId);
  const verification: Verification = {
    id: newId("vrf"),
    checkoutId,
    verifierSessionId: session.sessionId,
    requestUrl: session.requestUrl,
    expiresAt: Date.now() + config.verificationTtlSeconds * 1000,
    status: "pending",
    createdAt: new Date().toISOString(),
  };
  mutate((db) => {
    db.verifications[verification.id] = verification;
    db.checkouts[checkoutId].verificationId = verification.id;
  });
  return verification;
}

let rootPemCache: string | undefined;
function rootPem(): string {
  rootPemCache ??= readFileSync(config.rootCaPath, "utf8");
  return rootPemCache;
}

/**
 * Bring a wallet sign-in up to date with verifier2. On success, find or create the Lantern
 * account for the presented credential. Idempotent: terminal states are returned as stored.
 */
export async function advanceVerification(id: string): Promise<Verification | null> {
  const current = readDb().verifications[id];
  if (!current) return null;
  if (current.status !== "pending") return current;

  let info;
  try {
    info = await getVerificationInfo(current.verifierSessionId);
  } catch {
    return current; // verifier2 unreachable: stay pending, the page keeps polling
  }
  const outcome = mapVerifierInfo(info, ACCEPTED_CREDENTIALS);

  if (outcome.state === "pending") {
    if (Date.now() > current.expiresAt) return settle(id, { status: "expired", message: "This sign-in request expired." });
    return current;
  }
  if (outcome.state === "expired") return settle(id, { status: "expired", message: "This sign-in request expired." });
  if (outcome.state === "failed") return settle(id, { status: "failed", message: outcome.reason });

  const { credential, claims } = outcome;
  let trust;
  try {
    trust = checkIssuerTrust({
      x5c: outcome.x5c,
      rootPem: rootPem(),
      expectedHost: credential.issuerCertHost,
      expectedIssuer: credential.issuer,
      issuer: outcome.issuer,
      verifiedKey: outcome.issuerPublicKey,
    });
  } catch {
    trust = { ok: false as const, reason: "Could not check the issuer certificate." };
  }
  if (!trust.ok) return settle(id, { status: "failed", message: trust.reason });

  return mutate((db) => {
    const v = db.verifications[id];
    if (v.status !== "pending") return v; // a concurrent poll already settled it
    const now = new Date().toISOString();
    const key = credential.accountKey(claims);
    let account = Object.values(db.accounts).find((a) => a.key === key);
    const newAccount = !account;
    if (!account) {
      account = {
        id: newId("acct", 10),
        key,
        kind: credential.kind,
        credentialId: credential.id,
        displayName: credential.displayName(claims),
        claims,
        issuer: outcome.issuer,
        createdAt: now,
        lastSignInAt: now,
      } satisfies Account;
      db.accounts[account.id] = account;
    } else {
      Object.assign(account, { claims, displayName: credential.displayName(claims), issuer: outcome.issuer, lastSignInAt: now });
    }
    Object.assign(v, {
      status: "verified",
      accountId: account.id,
      newAccount,
      responseCode: info?.response_code ?? undefined,
      message: undefined,
    });
    return v;
  });
}

function settle(id: string, patch: Pick<Verification, "status" | "message">): Verification {
  return mutate((db) => {
    const v = db.verifications[id];
    if (v.status === "pending") Object.assign(v, patch);
    return v;
  });
}

export type SignInResult = { ok: true; sessionId: string } | { ok: false; error: string };

/**
 * Turn a verified wallet sign-in into a browser session. Only the browser that started the
 * checkout (it holds the checkout cookie) can do this, only with the response_code verifier2 put in
 * the wallet's continuation, and only once per verification. Polling alone never signs anyone in.
 */
export function consumeVerification(
  checkoutId: string,
  browserSecret: string | undefined,
  responseCode: string | null | undefined,
): SignInResult {
  return mutate((db) => {
    const checkout = db.checkouts[checkoutId];
    if (!checkout?.verificationId) return { ok: false as const, error: "No sign-in for this checkout." };
    if (!browserSecret || checkout.browserSecret !== browserSecret) {
      return { ok: false as const, error: "This sign-in belongs to another browser." };
    }
    const v = db.verifications[checkout.verificationId];
    if (v?.status !== "verified" || !v.accountId) return { ok: false as const, error: "The wallet sign-in is not complete." };
    if (!sameSecret(v.responseCode, responseCode)) {
      return { ok: false as const, error: "Continue with the link your wallet returned after signing in." };
    }
    if (v.consumedAt) return { ok: false as const, error: "This sign-in was already used." };
    const sessionId = newId("lses", 32);
    db.lanternSessions[sessionId] = {
      id: sessionId,
      accountId: v.accountId,
      newAccount: v.newAccount ?? false,
      verificationId: v.id,
      createdAt: new Date().toISOString(),
    };
    v.consumedAt = new Date().toISOString();
    return { ok: true as const, sessionId };
  });
}

function sameSecret(expected: string | undefined, given: string | null | undefined): boolean {
  if (!expected || !given) return false;
  const a = Buffer.from(expected);
  const b = Buffer.from(given);
  return a.length === b.length && timingSafeEqual(a, b);
}

export type HoldResult = { ok: true; orderId: string } | { ok: false; error: string };

/** Confirm: hold the checkout's seats on the signed-in account (cap 4 per account per event). */
export function holdSeats(checkoutId: string, accountId: string): HoldResult {
  return mutate((db) => {
    const checkout = db.checkouts[checkoutId];
    if (!checkout) return { ok: false as const, error: "No such checkout." };
    if (checkout.status === "held" && checkout.orderId) return { ok: true as const, orderId: checkout.orderId };
    const account = db.accounts[accountId];
    if (!account) return { ok: false as const, error: "Sign in again." };

    const taken = takenFor(db, checkout.id);
    const gone = checkout.seats.filter((s) => taken.has(s));
    if (gone.length) return { ok: false as const, error: `Seats ${gone.join(", ")} were taken in the meantime. Pick other seats.` };

    const cap = checkAccountCap(Object.values(db.orders), accountId, checkout.eventId, checkout.seats.length);
    if (!cap.ok) return { ok: false as const, error: cap.message };

    const orderId = newId("ord", 10);
    db.orders[orderId] = {
      id: orderId,
      eventId: checkout.eventId,
      seats: [...checkout.seats].sort(seatOrder),
      kind: account.kind,
      accountId,
      claims: account.claims,
      signedInAt: account.lastSignInAt,
      createdAt: new Date().toISOString(),
    };
    Object.assign(checkout, { status: "held", orderId });
    return { ok: true as const, orderId };
  });
}
