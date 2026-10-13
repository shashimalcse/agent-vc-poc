import "server-only";
import { existsSync, mkdirSync, readFileSync, renameSync, writeFileSync } from "node:fs";
import path from "node:path";
import { randomBytes } from "node:crypto";
import { config } from "./config";
import type { Provider, AccreditationLevel } from "./agent";

// A tiny JSON-file store under web/.data/ (gitignored). Each operation re-reads the file and
// writes it back atomically, so it survives dev-server reloads. Single process only.
// `pnpm reset` (scripts/reset-demo.mjs) clears it for a clean demo take.

// ---- Agent Registry ----
export type Agent = {
  agentId: string;
  name: string;
  provider: Provider;
  accreditationLevel: AccreditationLevel;
  operatorName: string;
  createdAt: string;
};
export type Offer = {
  offerId: string;
  agentId: string;
  credentialOffer: string;
  /** epoch ms */
  expiresAt: number;
  createdAt: string;
  /** Set once issuer2 reports the session SUCCESSFUL. */
  issuedAt?: string;
};

// ---- Lantern Tickets ----
/** A Lantern account, created from the first credential that signs in with it. */
export type Account = {
  id: string;
  /** e.g. "agent:agt_xSMw0m" (see credentialTypes.ts accountKey) */
  key: string;
  kind: "agent" | "person";
  /** DCQL credential id that created it, e.g. "agent" */
  credentialId: string;
  displayName: string;
  /** Disclosed claims from the latest sign-in. */
  claims: Record<string, string>;
  issuer: string;
  createdAt: string;
  lastSignInAt: string;
};
export type LanternSession = {
  id: string;
  accountId: string;
  /** True when this sign-in created the account. */
  newAccount: boolean;
  verificationId: string;
  createdAt: string;
};
export type Checkout = {
  id: string;
  eventId: string;
  seats: string[];
  status: "open" | "held";
  /** Seats stay reserved for this checkout until then (epoch ms). */
  reservedUntil?: number;
  /** Random value also held in the browser's checkout cookie. */
  browserSecret?: string;
  verificationId?: string;
  orderId?: string;
  createdAt: string;
};
export type Verification = {
  id: string;
  checkoutId: string;
  verifierSessionId: string;
  requestUrl: string;
  /** epoch ms */
  expiresAt: number;
  status: "pending" | "verified" | "failed" | "expired";
  message?: string;
  /** On success: the account the credential signs in to. */
  accountId?: string;
  newAccount?: boolean;
  /** Set when a browser session was created from this verification (single use). */
  consumedAt?: string;
  createdAt: string;
};
export type Order = {
  id: string;
  eventId: string;
  seats: string[];
  /** "house": seats pre-sold by the demo seed, not tied to an account. */
  kind: "agent" | "person" | "house";
  accountId?: string;
  /** Snapshot of the credential claims the account signed in with. */
  claims?: Record<string, string>;
  signedInAt?: string;
  createdAt: string;
};

export type Db = {
  agents: Record<string, Agent>;
  offers: Record<string, Offer>;
  accounts: Record<string, Account>;
  lanternSessions: Record<string, LanternSession>;
  checkouts: Record<string, Checkout>;
  verifications: Record<string, Verification>;
  orders: Record<string, Order>;
};

const empty = (): Db => ({
  agents: {},
  offers: {},
  accounts: {},
  lanternSessions: {},
  checkouts: {},
  verifications: {},
  orders: {},
});

const file = () => path.join(config.dataDir, "db.json");

export function readDb(): Db {
  const f = file();
  if (!existsSync(f)) return empty();
  return { ...empty(), ...(JSON.parse(readFileSync(f, "utf8")) as Partial<Db>) };
}

/** Read-modify-write. `fn` runs synchronously, so no other request interleaves. */
export function mutate<T>(fn: (db: Db) => T): T {
  const db = readDb();
  const result = fn(db);
  mkdirSync(config.dataDir, { recursive: true });
  const tmp = `${file()}.${process.pid}.tmp`;
  writeFileSync(tmp, JSON.stringify(db, null, 2), { mode: 0o600 });
  renameSync(tmp, file());
  return result;
}

const ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";

/** Random base62 string (rejection sampling, no modulo bias). */
export function randomId(length: number): string {
  let out = "";
  while (out.length < length) {
    for (const b of randomBytes(length * 2)) {
      if (b < 248 && out.length < length) out += ALPHABET[b % 62];
    }
  }
  return out;
}

export const newId = (prefix: string, length = 16) => `${prefix}_${randomId(length)}`;
