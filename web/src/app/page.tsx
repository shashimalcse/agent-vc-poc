import { config } from "@/lib/config";

// Only reachable on localhost:3100/ (the public hosts rewrite / to their own site).
export default function DevIndex() {
  return (
    <main className="dev-index">
      <h1>Agent credential PoC</h1>
      <p>One Next.js app, two fictional sites. Behind Caddy each has its own host.</p>
      <ul>
        <li>
          Agent Registry: <a href="/registry">/registry</a> · <a href={config.registryPublicUrl}>{config.registryPublicUrl}</a>
        </li>
        <li>
          Lantern Tickets: <a href="/tickets">/tickets</a> · <a href={config.ticketsPublicUrl}>{config.ticketsPublicUrl}</a>
        </li>
      </ul>
    </main>
  );
}
