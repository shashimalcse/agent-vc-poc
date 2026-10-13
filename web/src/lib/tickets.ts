// Lantern Tickets: the one event, its seat map, reservations and the per-account cap.
// Pure functions only.

export const EVENT = {
  id: "lantern-hall-friday",
  title: "Friday at Lantern Hall",
  doors: "19:00",
  show: "20:00",
} as const;

export const ROWS = ["A", "B", "C", "D"] as const;
export const SEATS_PER_ROW = 10;
/** Most seats one checkout may select, and most seats one account may hold for the event. */
export const MAX_SEATS = 4;

export const ALL_SEATS: readonly string[] = ROWS.flatMap((r) =>
  Array.from({ length: SEATS_PER_ROW }, (_, i) => `${r}${i + 1}`),
);

export function isSeat(s: string): boolean {
  return ALL_SEATS.includes(s);
}

/** Next Friday on or after `from` (local time). */
export function upcomingFriday(from: Date): Date {
  const d = new Date(from);
  d.setHours(0, 0, 0, 0);
  d.setDate(d.getDate() + ((5 - d.getDay() + 7) % 7));
  return d;
}

/** "FRI 16 OCT" */
export function shortDate(d: Date): string {
  return d
    .toLocaleDateString("en-GB", { weekday: "short", day: "numeric", month: "short" })
    .replace(",", "")
    .toUpperCase();
}

/** "Fri 16 Oct" */
export function mediumDate(d: Date): string {
  return d.toLocaleDateString("en-GB", { weekday: "short", day: "numeric", month: "short" }).replace(",", "");
}

export type SeatSelection = { ok: true; seats: string[] } | { ok: false; error: string };

export function validateSelection(raw: string[], taken: ReadonlySet<string>): SeatSelection {
  const seats = [...new Set(raw.map((s) => s.trim().toUpperCase()).filter(Boolean))];
  if (seats.length === 0) return { ok: false, error: "Pick at least one seat." };
  if (seats.length > MAX_SEATS) return { ok: false, error: `You can hold up to ${MAX_SEATS} tickets.` };
  const unknown = seats.filter((s) => !isSeat(s));
  if (unknown.length) return { ok: false, error: `No such seat: ${unknown.join(", ")}.` };
  const gone = seats.filter((s) => taken.has(s));
  if (gone.length) return { ok: false, error: `Already taken: ${gone.join(", ")}.` };
  return { ok: true, seats: seats.sort(seatOrder) };
}

export function seatOrder(a: string, b: string): number {
  return a[0] === b[0] ? Number(a.slice(1)) - Number(b.slice(1)) : a.localeCompare(b);
}

/** "B4 and B5", "A1, A2 and A3" */
export function seatList(seats: readonly string[]): string {
  if (seats.length <= 1) return seats.join("");
  return `${seats.slice(0, -1).join(", ")} and ${seats[seats.length - 1]}`;
}

const WORDS = ["Zero", "One", "Two", "Three", "Four"];
export const countWord = (n: number) => WORDS[n] ?? String(n);

type HeldOrder = { eventId: string; seats: string[]; accountId?: string };
type Reservation = { id: string; eventId: string; seats: string[]; status: string; reservedUntil?: number };

export type CapCheck =
  | { ok: true; alreadyHeld: number; remaining: number }
  | { ok: false; alreadyHeld: number; remaining: number; message: string };

/**
 * Cap: at most MAX_SEATS tickets per Lantern account per event, across all of its orders.
 * An agent's account is keyed by its agent_id, so this is also 4 per agent_id.
 */
export function checkAccountCap(
  orders: Iterable<HeldOrder>,
  accountId: string,
  eventId: string,
  requested: number,
  cap = MAX_SEATS,
): CapCheck {
  let alreadyHeld = 0;
  for (const o of orders) {
    if (o.eventId === eventId && o.accountId === accountId) alreadyHeld += o.seats.length;
  }
  const remaining = Math.max(0, cap - alreadyHeld);
  if (requested <= remaining) return { ok: true, alreadyHeld, remaining };
  const message =
    remaining === 0
      ? `This account already holds ${alreadyHeld} tickets for this show, the most one account can hold (${cap}).`
      : `This account already holds ${alreadyHeld} tickets for this show and can hold ${remaining} more (limit ${cap} per account). Choose ${remaining} or fewer seats.`;
  return { ok: false, alreadyHeld, remaining, message };
}

/** Seats sold, plus seats reserved by other checkouts that have not timed out. */
export function takenSeats(
  orders: Iterable<HeldOrder>,
  eventId: string,
  reservations: Iterable<Reservation> = [],
  now = Date.now(),
  exceptCheckoutId?: string,
): Set<string> {
  const taken = new Set<string>();
  for (const o of orders) if (o.eventId === eventId) o.seats.forEach((s) => taken.add(s));
  for (const r of reservations) {
    if (r.eventId !== eventId || r.status !== "open" || r.id === exceptCheckoutId) continue;
    if ((r.reservedUntil ?? 0) > now) r.seats.forEach((s) => taken.add(s));
  }
  return taken;
}

/** The date of this week's show (the coming Friday). */
export function currentEventDate(): Date {
  return upcomingFriday(new Date());
}
