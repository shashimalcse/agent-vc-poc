import { describe, expect, it } from "vitest";
import { normalizeHost, resolveRoute, sitePath } from "./hostRouting";

describe("resolveRoute", () => {
  it("rewrites the registry host onto /registry", () => {
    expect(resolveRoute({ host: "registry.localtest.me", pathname: "/" })).toEqual({
      kind: "rewrite",
      site: "registry",
      base: "",
      pathname: "/registry",
    });
    expect(resolveRoute({ host: "registry.localtest.me", pathname: "/agents/agt_abc123" })).toMatchObject({
      kind: "rewrite",
      pathname: "/registry/agents/agt_abc123",
    });
  });

  it("rewrites the tickets host onto /tickets", () => {
    expect(resolveRoute({ host: "tickets.localtest.me", pathname: "/checkout/chk_1" })).toMatchObject({
      kind: "rewrite",
      site: "tickets",
      pathname: "/tickets/checkout/chk_1",
    });
  });

  it("serves the vct URL from the registry site", () => {
    expect(resolveRoute({ host: "registry.localtest.me", pathname: "/credentials/agent/v1" })).toMatchObject({
      kind: "rewrite",
      pathname: "/registry/credentials/agent/v1",
    });
  });

  it("prefers X-Forwarded-Host over Host, and ignores ports and case", () => {
    expect(
      resolveRoute({ host: "host.docker.internal:3100", forwardedHost: "Tickets.LocalTest.me:443", pathname: "/" }),
    ).toMatchObject({ kind: "rewrite", site: "tickets", pathname: "/tickets" });
    expect(resolveRoute({ host: "localhost:3100", forwardedHost: "tickets.localtest.me, proxy", pathname: "/" }))
      .toMatchObject({ site: "tickets" });
  });

  it("does not leak one site's pages onto the other host", () => {
    expect(resolveRoute({ host: "tickets.localtest.me", pathname: "/registry/agents" })).toEqual({
      kind: "not-found",
      site: "tickets",
    });
    expect(resolveRoute({ host: "registry.localtest.me", pathname: "/api/tickets/verification/x" })).toEqual({
      kind: "not-found",
      site: "registry",
    });
  });

  it("passes framework assets and the site's own API through on public hosts", () => {
    expect(resolveRoute({ host: "tickets.localtest.me", pathname: "/_next/static/chunk.js" })).toEqual({
      kind: "next",
      site: "tickets",
      base: "",
    });
    expect(resolveRoute({ host: "tickets.localtest.me", pathname: "/api/tickets/verification/vrf_1" })).toEqual({
      kind: "next",
      site: "tickets",
      base: "",
    });
  });

  it("keeps already-prefixed paths on their own host", () => {
    expect(resolveRoute({ host: "tickets.localtest.me", pathname: "/tickets/orders/x" })).toEqual({
      kind: "next",
      site: "tickets",
      base: "",
    });
  });

  it("serves both sites under prefixes on localhost", () => {
    expect(resolveRoute({ host: "localhost:3100", pathname: "/registry/agents" })).toEqual({
      kind: "next",
      site: "registry",
      base: "/registry",
    });
    expect(resolveRoute({ host: "127.0.0.1:3100", pathname: "/tickets" })).toEqual({
      kind: "next",
      site: "tickets",
      base: "/tickets",
    });
    expect(resolveRoute({ host: "localhost:3100", pathname: "/api/tickets/verification/v" })).toEqual({
      kind: "next",
      site: "tickets",
      base: "/tickets",
    });
    expect(resolveRoute({ host: "localhost:3100", pathname: "/" })).toEqual({ kind: "next", site: null, base: "" });
  });

  it("does not treat look-alike prefixes as a site", () => {
    expect(resolveRoute({ host: "localhost", pathname: "/ticketsx" })).toEqual({ kind: "next", site: null, base: "" });
  });
});

describe("normalizeHost", () => {
  it("handles IPv6 and empty values", () => {
    expect(normalizeHost("[::1]:3100")).toBe("[::1]");
    expect(normalizeHost(undefined)).toBe("");
  });
});

describe("sitePath", () => {
  it("joins base and path", () => {
    expect(sitePath("", "/agents")).toBe("/agents");
    expect(sitePath("/registry", "/agents")).toBe("/registry/agents");
    expect(sitePath("/registry", "/")).toBe("/registry");
    expect(sitePath("", "/")).toBe("/");
  });
});
