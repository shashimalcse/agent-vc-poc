import "server-only";
import { headers } from "next/headers";
import QRCode from "qrcode";
import { sitePath, type Site } from "./hostRouting";

/** Link base for the current request: "" on the public hosts, "/registry" or "/tickets" on localhost. */
export async function siteBase(site: Site): Promise<string> {
  const h = await headers();
  const base = h.get("x-site-base");
  return base === null ? `/${site}` : base;
}

/** Returns a function that builds site-relative links for the current request. */
export async function linker(site: Site): Promise<(path: string) => string> {
  const base = await siteBase(site);
  return (path: string) => sitePath(base, path);
}

/** True when the browser reached us over HTTPS (Caddy sets X-Forwarded-Proto). */
export async function isHttps(): Promise<boolean> {
  const h = await headers();
  return (h.get("x-forwarded-proto") ?? "").split(",")[0].trim() === "https";
}

/** QR code as inline SVG markup (generated on the server, no client JS). */
export async function qrSvg(text: string, colors?: { dark: string; light: string }): Promise<string> {
  return QRCode.toString(text, { type: "svg", errorCorrectionLevel: "M", margin: 0, color: colors });
}
