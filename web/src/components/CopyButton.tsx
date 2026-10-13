"use client";

import { useState } from "react";
import { CopyIcon } from "./icons";

export function CopyButton({ text, id }: { text: string; id?: string }) {
  const [copied, setCopied] = useState(false);
  return (
    <button
      type="button"
      className="copy-btn"
      id={id}
      onClick={async () => {
        try {
          await navigator.clipboard.writeText(text);
          setCopied(true);
          setTimeout(() => setCopied(false), 2000);
        } catch {
          // Clipboard blocked (e.g. plain http): the link itself stays on the page.
        }
      }}
    >
      <CopyIcon />
      <span aria-live="polite">{copied ? "Copied" : "Copy"}</span>
    </button>
  );
}
