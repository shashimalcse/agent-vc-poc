// Host-based routing for the two fictional sites served by this one app.
// Pure and framework-free so it can be unit tested; src/proxy.ts applies it.

export type Site = "registry" | "tickets";

export const SITES: readonly Site[] = ["registry", "tickets"];

/** Public hostnames (behind Caddy) and the site each one serves. */
export const SITE_HOSTS: Readonly<Record<string, Site>> = {
  "registry.localtest.me": "registry",
  "tickets.localtest.me": "tickets",
};

export type RouteDecision =
  /** Serve `pathname` as is. */
  | { kind: "next"; site: Site | null; base: string }
  /** Serve the internal path `pathname` instead of the requested one. */
  | { kind: "rewrite"; site: Site; base: string; pathname: string }
  /** The path belongs to the other site; answer 404 instead of leaking it across hosts. */
  | { kind: "not-found"; site: Site };

/** First value of a possibly comma-separated header, lower-cased, without the port. */
export function normalizeHost(raw: string | null | undefined): string {
  if (!raw) return "";
  const first = raw.split(",")[0].trim().toLowerCase();
  // [::1]:3100 -> [::1]; host:port -> host
  if (first.startsWith("[")) return first.slice(0, first.indexOf("]") + 1);
  return first.replace(/:\d+$/, "");
}

function prefixOf(pathname: string): Site | null {
  for (const site of SITES) {
    if (pathname === `/${site}` || pathname.startsWith(`/${site}/`)) return site;
  }
  return null;
}

/** Paths that are never rewritten: framework assets and the shared API namespace. */
function isPassthrough(pathname: string): boolean {
  return (
    pathname.startsWith("/_next/") ||
    pathname === "/favicon.ico" ||
    pathname === "/robots.txt" ||
    pathname === "/api" ||
    pathname.startsWith("/api/")
  );
}

/**
 * Decide how to serve a request.
 *
 * - On `registry.localtest.me` / `tickets.localtest.me` (X-Forwarded-Host wins over Host),
 *   `/x` is served from `/<site>/x`, and links use an empty base.
 * - Anywhere else (e.g. `localhost:3100`), paths are served as is and each site lives under
 *   `/registry` or `/tickets`, which is also its link base.
 * - `/api/<site>/...` is shared by both forms; the base follows the host.
 */
export function resolveRoute(input: {
  host: string | null | undefined;
  forwardedHost?: string | null;
  pathname: string;
}): RouteDecision {
  const host = normalizeHost(input.forwardedHost || input.host);
  const hostSite = SITE_HOSTS[host] ?? null;
  const { pathname } = input;

  if (hostSite) {
    if (isPassthrough(pathname)) {
      const apiSite = apiSiteOf(pathname);
      if (apiSite && apiSite !== hostSite) return { kind: "not-found", site: hostSite };
      return { kind: "next", site: hostSite, base: "" };
    }
    const prefixed = prefixOf(pathname);
    if (prefixed === hostSite) return { kind: "next", site: hostSite, base: "" };
    if (prefixed) return { kind: "not-found", site: hostSite };
    return {
      kind: "rewrite",
      site: hostSite,
      base: "",
      pathname: `/${hostSite}${pathname === "/" ? "" : pathname}`,
    };
  }

  const site = prefixOf(pathname) ?? apiSiteOf(pathname);
  return { kind: "next", site, base: site ? `/${site}` : "" };
}

function apiSiteOf(pathname: string): Site | null {
  const m = /^\/api\/([^/]+)/.exec(pathname);
  return m && (SITES as readonly string[]).includes(m[1]) ? (m[1] as Site) : null;
}

/** Build a site-relative link from a base ("" or "/registry") and a path ("/agents"). */
export function sitePath(base: string, path: string): string {
  const p = path.startsWith("/") ? path : `/${path}`;
  if (!base) return p;
  return p === "/" ? base : `${base}${p}`;
}
