"use client";

import { useRouter } from "next/navigation";
import { useCallback, useEffect, useState } from "react";
import { Countdown } from "./Countdown";

type Status = { status: string; message?: string; nextUrl?: string };

// "Waiting for your wallet…" with a live countdown. Polls Lantern's own route handler (never
// verifier2) and advances on its own when the wallet has signed in. Without JS, the page's
// "Check status" link does the same on the server.
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
          if (s.status === "verified" && s.nextUrl) {
            setDone(true);
            window.location.assign(s.nextUrl);
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
      <span>{done ? "Signed in. Opening your account…" : "Waiting for your wallet…"}</span>
      {!done && (
        <span className="status-expiry">
          <Countdown expiresAt={expiresAt} initial={initialRemaining} onExpire={refresh} />
        </span>
      )}
    </div>
  );
}
