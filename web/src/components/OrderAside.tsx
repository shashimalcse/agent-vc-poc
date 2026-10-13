import { EVENT, currentEventDate, mediumDate } from "@/lib/tickets";

/** "Your order" aside used on the sign-in step. */
export function OrderAside({ seats }: { seats: string[] }) {
  const date = currentEventDate();
  return (
    <aside aria-labelledby="order-title" className="lan-card order">
      <h2 id="order-title" className="lan-h2 small">
        Your order
      </h2>
      <SeatChips seats={seats} />
      <dl className="lan-dl ruled">
        <dt>Event</dt>
        <dd>{EVENT.title}</dd>
        <dt>Show</dt>
        <dd>
          {mediumDate(date)}, {EVENT.show}
        </dd>
        <dt>Tickets</dt>
        <dd>{seats.length}</dd>
      </dl>
    </aside>
  );
}

export function SeatChips({ seats, id }: { seats: string[]; id?: string }) {
  return (
    <div className="chips" id={id}>
      {seats.map((s) => (
        <span className="seat-chip" key={s}>
          {s}
        </span>
      ))}
    </div>
  );
}
