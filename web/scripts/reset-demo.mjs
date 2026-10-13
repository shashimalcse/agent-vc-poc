#!/usr/bin/env node
// Reset demo data for a clean video take. Safe while `pnpm dev` runs: every request re-reads
// .data/db.json.
//
//   pnpm reset                  wipe everything, then seed (house seats + "Research helper")
//   pnpm reset --keep-agents    keep Registry agents and offers, reset Lantern only
//   pnpm reset --no-seed        wipe without seeding
//
// Browsers keep their old cookies, but those sessions no longer exist, so they show as
// signed out. Credentials already in a wallet stay there (that is the wallet's state).
import { existsSync, mkdirSync, readFileSync, renameSync, writeFileSync } from "node:fs";
import { randomBytes } from "node:crypto";
import path from "node:path";

const args = new Set(process.argv.slice(2));
const dataDir = process.env.DATA_DIR ?? path.resolve(import.meta.dirname, "../.data");
const file = path.join(dataDir, "db.json");
const operatorName = process.env.OPERATOR_NAME?.trim() || "Ada Lovelace";
const EVENT_ID = "lantern-hall-friday";

const ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
const rid = (n) => [...randomBytes(n)].map((b) => ALPHABET[b % 62]).join("");

const old = existsSync(file) ? JSON.parse(readFileSync(file, "utf8")) : {};
const keepAgents = args.has("--keep-agents");
const db = {
  agents: keepAgents ? (old.agents ?? {}) : {},
  offers: keepAgents ? (old.offers ?? {}) : {},
  accounts: {},
  lanternSessions: {},
  checkouts: {},
  verifications: {},
  orders: {},
};

if (!args.has("--no-seed")) {
  // Seats already sold to other guests, as on the design canvas (TicketsEvent).
  const house = { A: [1, 2, 5, 6, 9], B: [1, 9, 10], C: [3, 4, 7], D: [2, 5, 6, 8] };
  const now = new Date().toISOString();
  const id = `ord_house${rid(5)}`;
  db.orders[id] = {
    id,
    eventId: EVENT_ID,
    seats: Object.entries(house).flatMap(([row, nums]) => nums.map((n) => `${row}${n}`)),
    kind: "house",
    createdAt: now,
  };
  // A second, not-yet-credentialed agent, so the Registry list matches the design.
  if (!keepAgents) {
    const agentId = `agt_${rid(6)}`;
    db.agents[agentId] = {
      agentId,
      name: "Research helper",
      provider: "Other",
      accreditationLevel: "basic",
      operatorName,
      createdAt: new Date(Date.now() - 86_400_000).toISOString(),
    };
  }
}

mkdirSync(dataDir, { recursive: true });
const tmp = `${file}.reset.tmp`;
writeFileSync(tmp, JSON.stringify(db, null, 2), { mode: 0o600 });
renameSync(tmp, file);
console.log(
  `Reset ${file}: ${Object.keys(db.agents).length} agent(s), ${Object.keys(db.offers).length} offer(s), ` +
    `${Object.values(db.orders).reduce((n, o) => n + o.seats.length, 0)} seat(s) pre-sold; Lantern accounts and sessions cleared.`,
);
