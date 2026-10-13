"use client";

import { useState } from "react";
import { ArrowIcon } from "./icons";

// Seat map and "Your selection", inside the server-rendered checkout <form>. Each seat is a
// real checkbox (name="seat", id="seat-B4"), so the form works without JS and agents can tick
// seats by label; JS only keeps the summary in sync and stops a fifth pick.
export function SeatPicker({
  rows,
  perRow,
  taken,
  max,
  eventTitle,
}: {
  rows: readonly string[];
  perRow: number;
  taken: string[];
  max: number;
  eventTitle: string;
}) {
  const [selected, setSelected] = useState<string[]>([]);
  const takenSet = new Set(taken);
  const full = selected.length >= max;

  const toggle = (seat: string, on: boolean) =>
    setSelected((prev) => {
      const next = on ? [...prev, seat] : prev.filter((s) => s !== seat);
      return [...new Set(next)].sort((a, b) =>
        a[0] === b[0] ? Number(a.slice(1)) - Number(b.slice(1)) : a.localeCompare(b),
      );
    });

  return (
    <>
      <section aria-labelledby="seats-title" className="lan-card seats">
        <div className="seat-head">
          <h2 id="seats-title" className="lan-h2">
            Choose seats
          </h2>
          <div className="seat-legend" aria-hidden="true">
            <span>
              <span className="swatch" />
              Free
            </span>
            <span>
              <span className="swatch yours" />
              Yours
            </span>
            <span>
              <span className="swatch taken" />
              Taken
            </span>
          </div>
        </div>
        <div className="stage" aria-hidden="true">
          STAGE
        </div>
        <fieldset className="seat-rows" id="seats">
          <legend className="visually-hidden">Seats, up to {max}</legend>
          {rows.map((row) => (
            <div className="seat-row" key={row} role="group" aria-label={`Row ${row}`}>
              <span className="row-name" aria-hidden="true">
                {row}
              </span>
              <div className="seat-cells">
                {Array.from({ length: perRow }, (_, i) => {
                  const seat = `${row}${i + 1}`;
                  const isTaken = takenSet.has(seat);
                  const isMine = selected.includes(seat);
                  return (
                    <label className="seat" key={seat}>
                      <input
                        type="checkbox"
                        name="seat"
                        value={seat}
                        id={`seat-${seat}`}
                        disabled={isTaken || (full && !isMine)}
                        checked={isMine}
                        onChange={(e) => toggle(seat, e.target.checked)}
                        aria-label={`Seat ${seat}, ${isTaken ? "taken" : "free"}`}
                      />
                      <span aria-hidden="true">{i + 1}</span>
                    </label>
                  );
                })}
              </div>
            </div>
          ))}
        </fieldset>
      </section>

      <aside aria-labelledby="summary-title" className="lan-card summary">
        <h2 id="summary-title" className="lan-h2">
          Your selection
        </h2>
        <div className="chips" aria-live="polite" id="selection">
          {selected.length === 0 ? (
            <span className="chips-empty">No seats yet. Pick up to {max}.</span>
          ) : (
            selected.map((s) => (
              <span className="seat-chip" key={s}>
                {s}
              </span>
            ))
          )}
        </div>
        <dl className="lan-dl ruled">
          <dt>Event</dt>
          <dd>{eventTitle}</dd>
          <dt>Tickets</dt>
          <dd>{selected.length}</dd>
        </dl>
        <button type="submit" className="lan-btn" id="checkout-button">
          Continue to checkout
          <ArrowIcon />
        </button>
      </aside>
    </>
  );
}
