import type { NextConfig } from "next";

// Every page here is per-request (cookie session, live issuer/verifier state), so Cache
// Components stays off: pages render fully on the server, without streamed Suspense shells,
// and the agent-facing links are plain HTML in the first response.
const nextConfig: NextConfig = {
  poweredByHeader: false,
  // `pnpm dev` behind Caddy: let the public hosts load dev-only assets (HMR, chunks).
  allowedDevOrigins: ["registry.localtest.me", "tickets.localtest.me"],
};

export default nextConfig;
