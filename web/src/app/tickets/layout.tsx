import type { Metadata } from "next";

export const metadata: Metadata = {
  title: { default: "Lantern Tickets", template: "%s · Lantern Tickets" },
  description: "Friday at Lantern Hall. Sign in with your wallet: people and accredited personal agents.",
};

// Each page renders its own <LanternHeader> (it shows the page's checkout step).
export default function TicketsLayout({ children }: { children: React.ReactNode }) {
  return <div className="lan">{children}</div>;
}
