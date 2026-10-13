"use client";

import { useRouter } from "next/navigation";
import { useCallback, useEffect } from "react";
import { ClockIcon } from "./icons";
import { Countdown } from "./Countdown";

// Registry offer state: "Waiting for your agent's wallet" plus a live countdown. Polls the
// portal's own status route (which reads issuer2 server-side) and re-renders the page as soon
// as the wallet collects the credential, so the card turns solid without a reload.
export function OfferWatcher({
  statusUrl,
  expiresAt,
  initialRemaining,
}: {
  statusUrl: string;
  expiresAt: number;
  initialRemaining: string;
}) {
  const router = useRouter();
  const refresh = useCallback(() => router.refresh(), [router]);

  useEffect(() => {
    let stopped = false;
    let timer: ReturnType<typeof setTimeout>;
    const tick = async () => {
      try {
        const res = await fetch(statusUrl, { cache: "no-store" });
        if (res.ok) {
          const { status } = (await res.json()) as { status: string };
          if (status !== "offer-created" && status !== "unknown") {
            refresh();
            return;
          }
        }
      } catch {
        // transient; keep polling
      }
      if (!stopped) timer = setTimeout(tick, 1500);
    };
    timer = setTimeout(tick, 1000);
    return () => {
      stopped = true;
      clearTimeout(timer);
    };
  }, [statusUrl, refresh]);

  return (
    <div className="offer-status" role="status" aria-live="polite" id="credential-offer-status">
      <div className="offer-waiting">
        <ClockIcon />
        Waiting for your agent&apos;s wallet
      </div>
      <p className="offer-expiry" id="credential-offer-expiry">
        <Countdown expiresAt={expiresAt} initial={initialRemaining} onExpire={refresh} />
      </p>
    </div>
  );
}
