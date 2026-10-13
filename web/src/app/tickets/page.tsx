import { LanternHeader } from "@/components/LanternHeader";
import { SeatPicker } from "@/components/SeatPicker";
import { takenFor } from "@/lib/checkout";
import { EVENT, MAX_SEATS, ROWS, SEATS_PER_ROW, currentEventDate, shortDate } from "@/lib/tickets";
import { startCheckoutAction } from "./actions";

export default async function EventPage({ searchParams }: { searchParams: Promise<{ error?: string }> }) {
  const { error } = await searchParams;
  const taken = [...takenFor()];
  const date = currentEventDate();

  return (
    <>
      <LanternHeader />
      <main className="lan-main event">
        <div className="lan-intro">
          <p className="lan-eyebrow">
            <time dateTime={date.toISOString().slice(0, 10)}>{shortDate(date)}</time> · DOORS {EVENT.doors} · SHOW{" "}
            {EVENT.show}
          </p>
          <h1 className="lan-h1 event">
            Friday at
            <br />
            Lantern Hall
          </h1>
          <p className="lan-lede">Live music in a small room. Pick your seats; you can hold up to {MAX_SEATS} tickets.</p>
        </div>

        {error && (
          <p className="lan-error" role="alert" id="seat-error">
            {error}
          </p>
        )}

        <form action={startCheckoutAction} id="seat-form" className="lan-row">
          <SeatPicker rows={ROWS} perRow={SEATS_PER_ROW} taken={taken} max={MAX_SEATS} eventTitle={EVENT.title} />
        </form>
      </main>
    </>
  );
}
