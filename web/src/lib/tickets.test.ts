import { describe, expect, it } from "vitest";
import { checkAccountCap, countWord, seatList, takenSeats, upcomingFriday, validateSelection } from "./tickets";

const EVENT = "lantern-hall-friday";
const order = (seats: string[], accountId?: string, eventId = EVENT) => ({
  eventId,
  seats,
  ...(accountId ? { accountId } : {}),
});

describe("checkAccountCap", () => {
  it("allows up to 4 tickets for a new account", () => {
    expect(checkAccountCap([], "acct_a", EVENT, 4)).toEqual({ ok: true, alreadyHeld: 0, remaining: 4 });
  });

  it("counts every order of the same account for the same event", () => {
    const orders = [order(["A1", "A2"], "acct_a"), order(["B1"], "acct_a")];
    expect(checkAccountCap(orders, "acct_a", EVENT, 1)).toMatchObject({ ok: true, alreadyHeld: 3, remaining: 1 });
    const over = checkAccountCap(orders, "acct_a", EVENT, 2);
    expect(over.ok).toBe(false);
    expect(over).toMatchObject({ alreadyHeld: 3, remaining: 1 });
    if (!over.ok) expect(over.message).toMatch(/can hold 1 more/);
  });

  it("rejects with a clear message once the account holds 4", () => {
    const res = checkAccountCap([order(["A1", "A2", "A3", "A4"], "acct_a")], "acct_a", EVENT, 1);
    expect(res.ok).toBe(false);
    if (!res.ok) expect(res.message).toMatch(/already holds 4 tickets/);
  });

  it("ignores other accounts, house seats and other events", () => {
    const orders = [
      order(["A1", "A2", "A3", "A4"], "acct_b"),
      order(["B1", "B2", "B3", "B4"]),
      order(["C1", "C2", "C3", "C4"], "acct_a", "another-event"),
    ];
    expect(checkAccountCap(orders, "acct_a", EVENT, 4)).toMatchObject({ ok: true, alreadyHeld: 0 });
  });
});

describe("takenSeats", () => {
  const now = 1_000_000;
  const res = (id: string, seats: string[], reservedUntil: number, status = "open") => ({
    id,
    eventId: EVENT,
    seats,
    status,
    reservedUntil,
  });
  it("counts sold seats and live reservations, not expired or own ones", () => {
    const taken = takenSeats(
      [order(["A1"])],
      EVENT,
      [res("c1", ["B1"], now + 1), res("c2", ["B2"], now - 1), res("c3", ["B3"], now + 1), res("c4", ["B4"], now + 1, "held")],
      now,
      "c3",
    );
    expect([...taken].sort()).toEqual(["A1", "B1"]);
  });
});

describe("validateSelection", () => {
  const none = new Set<string>();
  it("accepts 1 to 4 known, free seats and normalises them", () => {
    expect(validateSelection(["b5", "B4", "B4"], none)).toEqual({ ok: true, seats: ["B4", "B5"] });
    expect(validateSelection(["A10", "A9"], none)).toEqual({ ok: true, seats: ["A9", "A10"] });
  });
  it("rejects empty, too many, unknown and taken seats", () => {
    expect(validateSelection([], none).ok).toBe(false);
    expect(validateSelection(["A1", "A2", "A3", "A4", "A5"], none).ok).toBe(false);
    expect(validateSelection(["E1"], none).ok).toBe(false); // the hall has rows A-D
    expect(validateSelection(["A1"], takenSeats([order(["A1"])], EVENT)).ok).toBe(false);
  });
});

describe("copy helpers", () => {
  it("lists seats and counts in words", () => {
    expect(seatList(["B4", "B5"])).toBe("B4 and B5");
    expect(seatList(["A1", "A2", "A3"])).toBe("A1, A2 and A3");
    expect(countWord(2)).toBe("Two");
  });
});

describe("upcomingFriday", () => {
  it("returns the same day on a Friday and the next Friday otherwise", () => {
    expect(upcomingFriday(new Date(2026, 9, 16, 20)).getDate()).toBe(16); // Fri 16 Oct 2026
    expect(upcomingFriday(new Date(2026, 9, 12)).getDate()).toBe(16); // Mon
    expect(upcomingFriday(new Date(2026, 9, 17)).getDate()).toBe(23); // Sat
  });
});
