"use client";

import { useRouter } from "next/navigation";
import { useCallback, useEffect, useState } from "react";
import { Countdown } from "./Countdown";

type Status = { status: string; message?: string };

// "Waiting for your wallet…" with a live countdown. Polls Lantern's own route handler (never
// verifier2). It never signs in by itself: once the wallet has answered, the sign-in finishes only
// when this browser opens the link the wallet returned (its continuation). Without JS, the page's
// "Check status" link shows the same on the server.
export function SignInWatcher({
  verificationId,
  expiresAt,
  initialRemaining,
}: {
  verificationId: string;
  expiresAt: number;
  initialRemaining: string;
}) {
  const router = useRouter();
  const [done, setDone] = useState(false);
  const refresh = useCallback(() => router.refresh(), [router]);

  useEffect(() => {
    let stopped = false;
    let timer: ReturnType<typeof setTimeout>;
    const tick = async () => {
      try {
        const res = await fetch(`/api/tickets/verification/${encodeURIComponent(verificationId)}`, {
          cache: "no-store",
        });
        if (res.ok) {
          const s = (await res.json()) as Status;
          if (s.status === "verified") {
            setDone(true);
            return;
          }
          if (s.status !== "pending") {
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
  }, [verificationId, refresh]);

  return (
    <div role="status" aria-live="polite" className={`status-box${done ? " done" : ""}`} id="verification-status">
      <span className="status-dot" aria-hidden="true" />
      <span>{done ? "Your wallet has answered. Continue with the link it returned." : "Waiting for your wallet…"}</span>
      {!done && (
        <span className="status-expiry">
          <Countdown expiresAt={expiresAt} initial={initialRemaining} onExpire={refresh} />
        </span>
      )}
    </div>
  );
}
