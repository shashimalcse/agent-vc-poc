import { describe, expect, it, vi } from "vitest";

vi.mock("server-only", () => ({}));

const { buildSessionSetup } = await import("./verifier2");
const { config } = await import("./config");

describe("buildSessionSetup", () => {
  it("sends the wallet's continuation back to this checkout's signed-in route", () => {
    const setup = buildSessionSetup("chk_1") as { flow_type: string; redirects?: Record<string, string> };
    expect(setup.flow_type).toBe("cross_device");
    // verifier2 appends a fresh response_code to this URI; only the browser that opens it signs in.
    expect(setup.redirects?.success_redirect_uri).toBe(`${config.ticketsPublicUrl}/checkout/chk_1/signed-in`);
    expect(setup.redirects?.error_redirect_uri?.startsWith(`${config.ticketsPublicUrl}/checkout/chk_1/verify?error=`)).toBe(true);
  });

  it("has no redirects without a checkout", () => {
    expect((buildSessionSetup() as { redirects?: unknown }).redirects).toBeUndefined();
  });
});
