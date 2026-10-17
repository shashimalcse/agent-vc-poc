# web: Agent Registry and Lantern Tickets

One Next.js 16 app (App Router, TypeScript, pnpm) serves two fictional sites for the agent-credential PoC. The contract is [`docs/WIRING.md`](../docs/WIRING.md).

| Site | Public (via Caddy) | Local dev |
| --- | --- | --- |
| Agent Registry: register a personal agent, issue its agent credential through walt.id issuer2 (single operator, no sign-in) | https://registry.localtest.me | http://localhost:3100/registry |
| Lantern Tickets: pick seats, sign in with a wallet credential through walt.id verifier2, hold seats | https://tickets.localtest.me | http://localhost:3100/tickets |

walt.id config lives in [`infra/waltid/`](../infra/waltid). Its change log is [`infra/waltid/CHANGES.md`](../infra/waltid/CHANGES.md).

## Run

Prerequisites: Node 20+ and pnpm. Caddy, issuer2 and verifier2 run in Docker from `infra/`:

```bash
docker compose -f ../infra/docker-compose.yml --profile waltid up -d   # caddy, issuer-api2 :7005, verifier-api2 :7004
pnpm install
pnpm dev            # http://localhost:3100 (Caddy proxies both public hosts here)
# or: pnpm build && pnpm start
```

Checks:

```bash
pnpm test           # vitest: host routing, ticket cap and reservations, DCQL builder, verifier2 response mapping, issuer trust
pnpm lint
pnpm build
```

Smoke-test the walt.id services without the real wallet. `scripts/smoke-wallet.mjs` is a throwaway holder that keeps its key and credentials in `.data/smoke-holder.json`:

```bash
pnpm smoke:acquire 'openid-credential-offer://?credential_offer_uri=...'   # link from the agent page
pnpm smoke:present 'openid4vp://authorize?client_id=...'                    # link from the verify page
```

### Environment

All variables are optional; the defaults match WIRING.md.

| Variable | Default | Use |
| --- | --- | --- |
| `ISSUER2_INTERNAL_URL` | `http://localhost:7005` | issuer2 management API (create offers, read session status) |
| `VERIFIER2_INTERNAL_URL` | `http://localhost:7004` | verifier2 session API (create, `/info`) |
| `REGISTRY_PUBLIC_URL` | `https://registry.localtest.me` | public base URL of the portal (dev index links) |
| `TICKETS_PUBLIC_URL` | `https://tickets.localtest.me` | public base URL of the shop (dev index links) |
| `CREDENTIAL_ISSUER` | `https://issuer.localtest.me/openid4vci` | the only issuer (`iss`) the shop accepts |
| `ISSUER_CERT_HOST` | `issuer.localtest.me` | DNS SAN the issuer's x5c leaf must carry |
| `ROOT_CA_PATH` | `../infra/certs/rootCA.pem` | trust anchor for the issuer's x5c chain |
| `DATA_DIR` | `./.data` | JSON store (`db.json`; gitignored) |
| `OFFER_TTL_SECONDS` | `600` | credential offer lifetime |
| `VERIFICATION_TTL_SECONDS` | `600` | verifier2 session (wallet sign-in request) lifetime |
| `SEAT_HOLD_SECONDS` | `600` | how long picked seats stay reserved for a checkout |
| `OPERATOR_NAME` | `Ada Lovelace` | the Registry's single operator (header, new agents' `operator` claim) |

## How it works

The UI follows the approved design in [`docs/design/`](../docs/design) (Registry: white/ground and trust blue; Lantern: night and lantern amber; Geist, Geist Mono and Bricolage Grotesque via `next/font/google`, self-hosted at build). Motion is limited to CSS state transitions (credential card dashed → solid, status dot, chips and stubs fading in) and is off under `prefers-reduced-motion`.

- **Host routing** (`src/proxy.ts`, `src/lib/hostRouting.ts`). `registry.localtest.me` serves `/registry/...` and `tickets.localtest.me` serves `/tickets/...`. `X-Forwarded-Host` wins over `Host`. Each site's pages 404 on the other site's host. On `localhost:3100` both sites live under their prefixes. The proxy sets the trusted `x-site-base` request header, so pages build links like `/agents/...` on the public host and `/registry/agents/...` on localhost.
- **State.** `.data/db.json` is a small JSON file that is re-read and atomically rewritten on each change. It survives restarts. `pnpm reset` clears it (see below).
- **Agent Registry** (no sign-in; one operator from `OPERATOR_NAME`, shown in the header).
  - `/`: hero, **Register an agent** (name, runs on OpenClaw/Other, accreditation Basic/Verified) and **Your agents** with "Credential issued" / "Offer waiting" / "No credential yet". The `agent_id` is `agt_` plus 6 base62 characters.
  - `/agents/{agent_id}`: the credential card (dashed, "Not collected yet") and **Issue agent credential**. That calls `POST /issuer2/credential-offers` server-side (profile `AgentCredential`, pre-authorized, by reference, 600 s, claims as `runtimeOverrides.credentialData`).
  - Offer state: QR code, "Waiting for your agent's wallet" with a live "Expires in m:ss", the link `<a id="credential-offer-link">`, Copy, and the three steps. The page polls `/api/registry/offers/{offerId}` (`{status}`; issuer2 is read server-side and its session, which carries the signing key, never reaches the browser). When issuer2 reports `SUCCESSFUL`, the page re-renders without a reload: the card turns solid blue and "Your agent holds its credential" appears. "Issued" is stored, so it survives issuer2 restarts.
  - `https://registry.localtest.me/credentials/agent/v1` (the vct URL) serves SD-JWT VC type metadata.
- **Lantern Tickets** (one door: everyone signs in with a wallet credential). Steps Seats → Sign in → Confirm → Done; the header shows the step and, when signed in, a chip with the account.
  - `/`: seat map (rows A–D × 10) as a plain HTML form of checkboxes (`id="seat-B4"`); JS only keeps "Your selection" in sync and stops a fifth pick. Picked seats are reserved for 10 minutes.
  - `/checkout/{chk}`: "Sign in with your wallet" → **Continue with wallet** creates a verifier2 session. Signed-in browsers skip straight to Confirm. "Use email instead" goes to a page that says email sign-in is not part of the demo.
  - `/checkout/{chk}/verify`: QR code and `<a id="verify-agent-link" href="openid4vp://...">Open in wallet</a>` in server HTML, "Waiting for your wallet…" with a countdown, and "What we check before signing you in". JS polls `/api/tickets/verification/{vrf}` (`{status, message?}`; verifier2 internals never reach the browser) only to show status. It never signs in by itself: once the wallet has answered it says "Continue with the link it returned".
  - On `SUCCESSFUL` the server requires `overallSuccess`, an accepted vct and all requested claims. It checks the issuer x5c chain to `rootCA.pem`, the SAN, `iss`, and that the leaf key is the key verifier2 verified. It then finds or creates the Lantern account keyed by the credential (`agent:{agent_id}`).
  - The verifier2 session has `redirects.success_redirect_uri` = that checkout's `/signed-in`, so verifier2 answers the wallet's `direct_post` with that URL plus a fresh `response_code`. The wallet hands it to the agent as the A2WP -01 continuation.
  - `GET /checkout/{chk}/signed-in?response_code=...` turns that into a `lantern_session` cookie. It is single-use per verification, and only works for the browser holding that checkout's cookie (`lantern_chk_{chk}`) with the `response_code` verifier2 issued; it then redirects with a 303 to Confirm. A page that relayed someone else's request cannot be signed in by polling.
  - `/checkout/{chk}/confirm`: "Signed in with wallet · account created from an agent credential" on the first sign-in, "Signed in with wallet" after that. "Welcome, {agent_name}", the account details and **Hold my seats** (`id="hold-seats"`), which enforces **4 tickets per account per event** (4 per `agent_id`).
  - `/orders/{ord}`: "Seats held.", the ticket stubs, and the blue "Placed by" credential card.
- **Accepted credentials** (`src/lib/credentialTypes.ts`). Each accepted credential type is one DCQL credential query plus one option in a single required `credential_sets` entry. It also carries its issuer trust settings, claim checks and account key. Adding a person credential is one more entry in that list.

### Stable element ids (for browser agents)

| Page | ids |
| --- | --- |
| Registry home | `register-agent-form`, `agent-name`, `agent-provider`, `register-agent`, `agent-{agent_id}` |
| Registry agent page | `credential-card`, `issue-credential`, `credential-offer-link`, `copy-offer-link`, `credential-offer-status`, `credential-offer-expiry`, `credential-issued` |
| Event page | `seat-form`, `seat-{A1..D10}`, `selection`, `checkout-button`, `seat-error` |
| Sign in | `continue-with-wallet`, `email-signin-link` |
| Verify page | `verify-agent-link`, `verification-status`, `check-status-link`, `verification-error`, `retry-wallet-signin` |
| Confirm | `signed-in-status`, `welcome-heading`, `account-details`, `confirm-seats`, `hold-seats`, `hold-error` |
| Order page | `order-summary`, `order-seats`, `order-cap`, `placed-by`, `order-id` |
| Any Lantern page | `signed-in-as` (header chip, when signed in) |

### Reset demo data (clean video take)

```bash
pnpm reset                 # wipe .data/db.json, then seed: 15 pre-sold "house" seats (as on the design) and a "Research helper" agent
pnpm reset --keep-agents   # keep Registry agents and offers; reset Lantern accounts, sessions, checkouts and orders
pnpm reset --no-seed       # wipe without seeding
```

This is safe while `pnpm dev` runs. Browsers keep stale cookies, which then simply show as signed out. Credentials already in the agent's wallet stay there, so for a fully clean take also reset the wallet's store.

### Differences from the design

- The credential offer link is a real `<a id="credential-offer-link">` styled like the design's read-only field, so agents can read the href.
- **Continue with wallet** is a form button (it creates the verifier2 session), not a link.
- The agent page has an extra state the design does not show: no active offer yet, or the last offer expired. It shows the dashed card and an **Issue agent credential** button.
- The agents list adds an "Offer waiting" pill. The register form uses a placeholder instead of a prefilled name.
- The Lantern header chip appears on every page once signed in, not only on Confirm.
- "Venues" in the event nav is plain text, because there is no such page.
- The 10-minute seat reservation from the sign-in copy is real: other checkouts can't take those seats meanwhile.
- Copy uses "your agent's wallet" wherever the Registry refers to where the agent's credential lives. Offer step 03 reads "Your agent's wallet collects the credential".

## Verified on 2026-10-12

These ran against `waltid/issuer-api2:1.1.1` and `waltid/verifier-api2:1.1.1` behind Caddy.

- A portal offer resolves via `https://issuer.localtest.me/openid4vci/credential-offer?id=...`. The token, nonce and credential endpoints issue a `dc+sd-jwt` with x5c, `cnf.jwk`, `agent_id` plain and the other four claims as disclosures. The portal status then shows "Issued".
- A checkout session's `https://tickets.localtest.me/verification-session/{id}/request` returns an ES256 request object (`typ` `oauth-authz-req+jwt`). Its x5c leaf chains to `rootCA.pem`, the SAN is `tickets.localtest.me`, and `client_id` is `x509_san_dns:tickets.localtest.me`.
- Presenting to `/response` makes verifier2 report `SUCCESSFUL`. The shop signs the browser in to an account keyed by `agent_id`, and Hold my seats creates the order with the agent's claims. A later checkout for 3 more seats on the same account (2 already held) is refused with the cap message.
- The `openid4vp://` link was read from the server HTML of `https://tickets.localtest.me/checkout/.../verify`. The whole Lantern flow also completed without JS through the public host: plain HTML form posts, then the status API, `/signed-in`, Confirm and the order.
- verifier2 accepts the DCQL with `credential_sets`. The wallet agent confirmed its wallet handles it.

## Observations

walt.id issuer2 (1.1.1):
- `credential_issuer` is always `<baseUrl>/openid4vci`, and the SD-JWT `iss` defaults to it. With `baseUrl = https://issuer.localtest.me` the issuer identifier is `https://issuer.localtest.me/openid4vci`, not the bare host WIRING.md first assumed.
- The stock config rejects a pre-authorized token request without `client_id` (`401 invalid_client`, "Anonymous pre-authorized code access is not supported"). You must enable `clientAuthenticationConfig.supportedMethods = [{type = "preauth-anonymous"}]`.
- The management API (`/issuer2/profiles`, `/issuer2/sessions`, `/issuer2/credential-offers`) has no authentication. `GET /issuer2/profiles` and `GET /issuer2/sessions/{id}` return the issuer's private signing JWK (`d`), so a reverse proxy must expose only `/.well-known/*`, `/openid4vci/*` and `/static/*`.
- Offer creation is `POST /issuer2/credential-offers` with `{profileId, authMethod: "PRE_AUTHORIZED", valueMode, expiresInSeconds, runtimeOverrides.credentialData}`. It returns `{offerId, credentialOffer, expiresAt (epoch ms)}`, and `offerId` doubles as the session id for `GET /issuer2/sessions/{id}` (`status` `ACTIVE` → `SUCCESSFUL`).
- Pre-authorized codes are single-use (a second redemption gets `invalid_grant`). But the by-reference offer URL keeps answering 200 after the code is redeemed, and a `SUCCESSFUL` session stays `isClosed: false`.
- issuer2 rounds `iat` and `nbf` down to 00:00 UTC of the issuance day, and `exp` (from `<timestamp-in-seconds:90d>`) is counted from that rounded time.
- Signing material can be included from mounted files with HOCON `include file("/certs/x.jwk.json")`, but `x5Chain` must be inline PEM strings. Rotating a certificate therefore means editing config (`infra/waltid/sync-certs.mjs` does that).
- The access token is a plain `bearer` token; no DPoP is needed for the pre-authorized flow.

walt.id verifier2 (1.1.1):
- There is no `response_mode` field: `encrypted_response: false` gives `direct_post`, `true` gives `direct_post.jwt`. Signing is per session (`signed_request: true`). The source requires an explicit `clientId`, plus `x5c` for an `x509_*` client id (in `verifier-service.conf` or the request).
- `x509_san_dns` works as configured. The request object carries `x5c` (as configured), `iss` = client_id and `aud` `https://self-issued.me/v2`, but no `exp`.
- `POST /verification-session/create` returns a short `bootstrapAuthorizationRequestUrl` (`client_id` + `request_uri` + `request_uri_method=post`) and a long `fullAuthorizationRequestUrl` with the whole signed JAR inlined. The shop uses the bootstrap form for the link and QR code.
- The `request_uri` answers both GET and POST. That fits `request_uri_method=post`, but wallets that only GET still work.
- `GET /info` returns disclosed claims merged into `presented_credentials.<queryId>[0].credentialData`; `originalCredentialData` has the undisclosed view, `disclosures[]` has name/value pairs and `signature.jwtHeader.x5c` has the issuer chain. `policy_results.overallSuccess` is the top-level verdict. The `/info` body also carries the raw presentation (`presented_raw_data`), so it must not be passed to browsers.
- verifier2's `signature` policy resolves the issuer key from the x5c leaf without checking the chain to any root. The only trust-anchor policies are `etsi-trust-list` and `vical`, so the shop checks the issuer chain itself.
- verifier2 reports `SUCCESSFUL` with `overallSuccess: true` even when the wallet withholds DCQL-requested claims (seen by the wallet agent: only `agent_id` and `provider` disclosed). The shop therefore requires all five claims itself (`mapVerifierInfo`, unit-tested).
- Default `vc_policies` (when omitted) is just `signature`. Expiry, not-before and allowed-issuer must be requested explicitly.
- Without `expiration_duration`, `/info` shows no `expirationDate`. With it, the session starts as `UNUSED`, and becomes `IN_USE` once the wallet fetches the request.
- The KB-JWT `aud` must be the full client_id including the `x509_san_dns:` prefix, and `kb-jwt_iat-check` allows 5 minutes.

Next.js 16 / local setup:
- `create-next-app@16` turns on `cacheComponents`, which streams request-time data inside Suspense (hidden `<div>` + swap script). This app turns it off so the agent-facing links are plain HTML in the first response.
- A server-action redirect to the same path with only a new `#hash` does not re-render the page. The registry adds `?offer=<id>` to force a fetch.
- The dev server blocks dev assets for non-localhost origins unless `allowedDevOrigins` lists `registry.localtest.me` and `tickets.localtest.me`.
- Importing a plain helper (e.g. `formatRemaining`) from a `"use client"` module into a Server Component fails at runtime ("Attempted to call ... from the server"), so shared helpers live in non-client modules (`src/lib/time.ts`).
- On Rancher Desktop, `extra_hosts: host.docker.internal:host-gateway` resolves to the VM's docker bridge, not the Mac, so Caddy got 502 from Next on the host. The orchestrator removed the override.

WIRING.md (changed in this round, see the web agent report):
- The issuer identifier and `iss` are `https://issuer.localtest.me/openid4vci`, and the wallet's allowed issuer was updated to match.
- Issuer and verifier x5c are leaf-only and chain to `rootCA.pem`.
- Added the exact offer/request URL shapes, the policies and the KB-JWT `aud`. Following the session uses `/info` polling, not SSE.
