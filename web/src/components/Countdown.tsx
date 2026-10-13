"use client";

import { useEffect, useState } from "react";
import { formatRemaining } from "@/lib/time";


/** "Expires in 9:42", ticking every second. The server renders the first value. */
export function Countdown({
  expiresAt,
  initial,
  onExpire,
}: {
  expiresAt: number;
  initial: string;
  onExpire?: () => void;
}) {
  const [text, setText] = useState(initial);
  useEffect(() => {
    const tick = () => {
      const left = expiresAt - Date.now();
      setText(formatRemaining(left));
      if (left <= 0) {
        clearInterval(timer);
        onExpire?.();
      }
    };
    const timer = setInterval(tick, 1000);
    tick();
    return () => clearInterval(timer);
  }, [expiresAt, onExpire]);
  return <>Expires in {text}</>;
}
