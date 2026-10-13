# UI design

This folder holds the approved design for the promo video. The live canvas is the [UI design canvas](https://claude.ai/artifact/6DiP8JP8wig9bHSXkJyQwF); the files here are its snapshot as of 12 Oct 2026. Each `.dc.html` is one screen: plain HTML with inline styles. Ignore the `<x-dc>`, `<helmet>`, `<sc-for>` and `<script type="text/x-dc">` wrappers; they are canvas markup.

| Screen | File | Built in |
| --- | --- | --- |
| Registry · your agents and register | `Main.dc.html` | `web/` `/registry` |
| Registry · credential offer (card dashed, "Not collected yet") | `RegistryOffer.dc.html` | `web/` agent page, offer state |
| Registry · credential issued (card solid blue) | `RegistryIssued.dc.html` | `web/` agent page, issued state |
| Lantern · event and seats | `TicketsEvent.dc.html` | `web/` `/tickets` |
| Lantern · sign in with your wallet | `TicketsCheckout.dc.html` | `web/` checkout |
| Lantern · wallet sign-in request (QR + link) | `TicketsVerify.dc.html` | `web/` checkout, verify step |
| Lantern · welcome and confirm | `TicketsWelcome.dc.html` | `web/` checkout, confirm step |
| Lantern · seats held | `TicketsHeld.dc.html` | `web/` order page |
| Wallet · approve | `WalletApprove.dc.html` | `wallet/` approval page |
| Wallet · done | `WalletDone.dc.html` | `wallet/` approval result page |
| Agent chat | `AgentChat.dc.html` | not built (the agent's own chat app); video only |

## Design principles

- **One idea per screen.** Each screen is a single video beat.
- **State is the hero:** Offer created → Issued; Sign in → Welcome → Seats held. Status is big and legible, and it updates live without a reload, so motion can animate it.
- **Flat 2D:** no gradients, no 3D. Shapes, type and one accent per brand.
- **One credential card:** it appears in the Registry, the wallet and on Lantern's "Seats held".
- **One door for everyone:** Lantern never asks who is buying. People and agents both sign in with a wallet credential.
- **Agent-readable:** links are real links with visible text, rendered in server HTML, with stable ids.
- **Two brands:**
  - Registry: white `#FFFFFF` / ground `#F5F6F8`, ink `#0E1116`, muted `#4B5260`, line `#E3E6EB`, trust blue `#2443E6`, success `#0B6B49` on `#E6F6EF`.
  - Lantern: night `#131117`, surface `#1B1820`, line `#2B2732`, text `#F4EFE8`, muted `#B9B1C4`, lantern amber `#FFB547` (text on amber `#131117`).
- **Type:** Geist (UI), Geist Mono (IDs, codes), Bricolage Grotesque 800 (Lantern display).
