import { NextResponse, type NextRequest } from "next/server";
import { resolveRoute } from "@/lib/hostRouting";

// Maps registry.localtest.me -> /registry/... and tickets.localtest.me -> /tickets/...
// (Caddy forwards Host and X-Forwarded-Host). Also tells pages which link base to use.
export function proxy(request: NextRequest) {
  const decision = resolveRoute({
    host: request.headers.get("host"),
    forwardedHost: request.headers.get("x-forwarded-host"),
    pathname: request.nextUrl.pathname,
  });

  if (decision.kind === "not-found") {
    return new NextResponse("Not found", { status: 404, headers: { "content-type": "text/plain" } });
  }

  const headers = new Headers(request.headers);
  // Always overwrite: these are trusted only because the proxy sets them.
  headers.set("x-site", decision.site ?? "");
  headers.set("x-site-base", decision.base);

  if (decision.kind === "rewrite") {
    const url = request.nextUrl.clone();
    url.pathname = decision.pathname;
    return NextResponse.rewrite(url, { request: { headers } });
  }
  return NextResponse.next({ request: { headers } });
}

export const config = {
  matcher: ["/((?!_next/static|_next/image).*)"],
};
