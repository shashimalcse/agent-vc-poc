import type { Metadata } from "next";
import { notFound } from "next/navigation";
import { BackIcon } from "@/components/icons";
import { LanternHeader } from "@/components/LanternHeader";
import { linker } from "@/lib/request";
import { readDb } from "@/lib/store";

export const metadata: Metadata = { title: "Email sign-in" };

export default async function EmailSignInPage({ params }: { params: Promise<{ checkoutId: string }> }) {
  const to = await linker("tickets");
  const { checkoutId } = await params;
  if (!readDb().checkouts[checkoutId]) notFound();

  return (
    <>
      <LanternHeader step="signin" />
      <main className="lan-main">
        <div className="lan-intro">
          <a className="back-link" href={to(`/checkout/${checkoutId}`)}>
            <BackIcon />
            Back
          </a>
          <h1 className="lan-h1">
            Email sign-in isn&apos;t
            <br />
            part of this demo
          </h1>
          <p className="lan-lede">
            A real box office would send you a sign-in link here. This demo only shows signing in with a wallet
            credential.
          </p>
        </div>
        <a className="lan-btn outline" href={to(`/checkout/${checkoutId}`)} style={{ alignSelf: "flex-start" }}>
          Sign in with your wallet
        </a>
      </main>
    </>
  );
}
