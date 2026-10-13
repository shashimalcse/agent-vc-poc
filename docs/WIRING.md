# Wiring contract

This file is the single source of truth for how the parts connect. If a part needs to change the contract, it changes this file in the same change and says so in its report. Design rationale lives in the [PoC design doc](https://claude.ai/code/artifact/7fc6fb09-45f5-457c-97d4-f1a9e0d2b2e9). A2WP draft findings go in [A2WP-DRAFT-FEEDBACK.md](A2WP-DRAFT-FEEDBACK.md).

## Repository layout and owners

| Path | What | Owner |
| --- | --- | --- |
| `docs/` | This contract, draft feedback | orchestrator |
| `infra/` (except `infra/waltid/`) | Docker Compose, Caddy, certificates, scripts | orchestrator |
| `infra/waltid/` | issuer2 and verifier2 config (credential configuration, verifier signing, policies), mounted by Compose | `web` agent |
| `wallet/` | `a2wp-kt`: Kotlin A2WP wallet, remote MCP server | `wallet` agent |
| `web/` | Next.js app: Lantern Tickets and the Agent Registry portal | `web` agent |
| `agent-vm/` | Lima VM with OpenClaw (later) | orchestrator |

Each owner writes only inside its own path. Everything else is coordinated through this file.

## Hostnames, ports and TLS

`*.localtest.me` resolves to 127.0.0.1 through public DNS, so the Mac needs no hosts-file change. Caddy (in Docker, published on host port 443) terminates TLS with certificates issued by a local mkcert CA (`infra/certs/`).

| Public name | Routes to | Purpose |
| --- | --- | --- |
| `https://registry.localtest.me` | Next.js on host `:3100` | Agent Registry portal |
| `https://tickets.localtest.me` | Next.js on host `:3100` | Lantern Tickets |
| `https://tickets.localtest.me/verification-session/{id}/request` and `/response` only | verifier2 `:7004` | OpenID4VP request and response endpoints, so they share the verifier's `x509_san_dns` host |
| `https://issuer.localtest.me` | issuer2 `:7005` (only `/.well-known/*`, `/openid4vci/*`, `/static/*`) | OpenID4VCI credential issuer; `credential_issuer` is `https://issuer.localtest.me/openid4vci` |
| `https://wallet.localtest.me` | wallet on host `:8180` | `/mcp` (Streamable HTTP) and `/approve/...` (approval page) |

Internal, never through Caddy:
- Next.js calls verifier2 at `http://localhost:7004` (create session, info, SSE events).
- Next.js calls issuer2 at `http://localhost:7005` (create offers).

During development, Next.js (`pnpm dev -p 3100` on 3100) and the wallet (`:8180`) run directly on the Mac, and issuer2, verifier2 and Caddy run in Docker. Caddy reaches the host as `host.docker.internal`, and the Caddy container carries network aliases for all four public names, so containers resolve them to Caddy.

Trust:
- `infra/certs/rootCA.pem` (the mkcert CA) signs the TLS certificates, the verifier's request-signing certificate and the issuer's credential-signing certificate.
- The wallet must trust it for TLS, verifier request objects (x5c) and issuer credentials (x5c). Pass the path in the wallet config. Never install it system-wide from code.
- The wallet's SSRF guard blocks loopback and private addresses by default. A dev flag (`allowPrivateNetworks = true`) is needed because `*.localtest.me` resolves to 127.0.0.1.

## The agent credential

| Field | Value |
| --- | --- |
| Format | `dc+sd-jwt` (SD-JWT VC) with key binding (`cnf.jwk`) |
| `vct` | `https://registry.localtest.me/credentials/agent/v1` |
| Issuer (`iss`) | `https://issuer.localtest.me/openid4vci` (= `credential_issuer`; issuer2 always appends `/openid4vci` to its `baseUrl`) |
| Issuer signing | P-256 key, JWS header `x5c` = leaf only (SAN `issuer.localtest.me`), which chains to `infra/certs/rootCA.pem`; the root is not sent |
| Always disclosed | `agent_id` (opaque string, e.g. `agt_7Hk2pQ`) |
| Selectively disclosable | `agent_name`, `provider` (e.g. `OpenClaw`), `operator` (the user's display name), `accreditation_level` (`basic` or `verified`) |
| Validity | 90 days (`exp`); issuer2 rounds `iat`/`nbf` down to 00:00 UTC of the issuance day |
| issuer2 credential configuration id | `AgentCredential` |

## Issuance: Agent Registry → issuer2 → wallet

1. The portal (Next.js server) calls issuer2's internal API, `POST http://localhost:7005/issuer2/credential-offers` with `{ "profileId": "AgentCredential", "authMethod": "PRE_AUTHORIZED", "valueMode": "BY_REFERENCE", "expiresInSeconds": 600, "runtimeOverrides": { "credentialData": { ...claims } } }`.
2. The portal (single operator from `OPERATOR_NAME`, no sign-in) shows the offer on the agent page `/agents/{agent_id}` as a QR code and as a link, `openid-credential-offer://?credential_offer_uri=https%3A%2F%2Fissuer.localtest.me%2Fopenid4vci%2Fcredential-offer%3Fid%3D...` (by reference). The link is an `<a>` with `id="credential-offer-link"` (its text is the URL), with a Copy button (`id="copy-offer-link"`), a live "Expires in m:ss" (`id="credential-offer-expiry"`) and status (`id="credential-offer-status"`). The page polls `/api/registry/offers/{offerId}` (→ `{status}`, read from issuer2 server-side) and switches to the issued state (`id="credential-issued"`, solid card `id="credential-card"`) without a reload. The pre-authorized code is single-use (a second token request gets `invalid_grant`) and the offer expires after 10 minutes.
   - Wallet side: issuer metadata at `https://issuer.localtest.me/.well-known/openid-credential-issuer/openid4vci`, AS metadata at `https://issuer.localtest.me/.well-known/oauth-authorization-server/openid4vci`. The token request may omit `client_id` (issuer2 is configured for anonymous pre-authorized access). The access token is a plain `bearer` token (no DPoP). Proofs: `proofs.jwt` with ES256 and `jwk` in the header, `nonce` from the nonce endpoint.
3. The user pastes the link to the agent, and the agent calls the wallet's `acquire_credential` tool with it unchanged.
4. Later step: a `tx_code` (PIN shown on the portal, entered on the wallet's approval page).

## Verification: Lantern Tickets → verifier2 → wallet ("Sign in with your wallet")

Checkout flow (no "who is buying" choice; people and agents both sign in with a wallet credential). Paths are on `https://tickets.localtest.me` (or under `/tickets` on `localhost:3100`):

| Step | Page | Agent-relevant ids |
| --- | --- | --- |
| Seats | `/` (event, rows A–D × 10, up to 4 seats; plain HTML form) | checkboxes `seat-B4` …, `checkout-button` |
| Sign in | `/checkout/{chk}`: one card, **Continue with wallet** (seats reserved 10 min). Skipped when already signed in. "No wallet? Use email instead" → `/checkout/{chk}/email` (not part of the demo) | `continue-with-wallet`, `email-signin-link` |
| Sign in (wallet request) | `/checkout/{chk}/verify`: QR + `<a id="verify-agent-link" href="openid4vp://...">Open in wallet</a>`, waiting state, "What we check". Auto-advances on success (JS polls `/api/tickets/verification/{vrf}` → `{status, message?, nextUrl?}`); without JS, reload or `check-status-link` | `verify-agent-link`, `verification-status`, `check-status-link`, `verification-error` |
| (finish sign-in) | `GET /checkout/{chk}/signed-in`: single-use, only for the browser holding that checkout's cookie; sets the `lantern_session` cookie, 303 → confirm | |
| Confirm | `/checkout/{chk}/confirm`: "Welcome, {name}", account details, order, **Hold my seats** | `hold-seats`, `signed-in-status`, `account-details`, `hold-error` |
| Done | `/orders/{ord}`: ticket stubs and the "Placed by" credential card | `order-summary`, `order-seats`, `placed-by`, `order-id` |

Accounts: the first successful sign-in creates a Lantern account keyed by the credential (agent credential: `agent:{agent_id}`); later sign-ins with the same key find it ("Signed in with wallet"). The cap is 4 tickets per account per event, so 4 per `agent_id`.

1. At "Continue with wallet", the Next.js server calls `POST http://localhost:7004/verification-session/create` with:
   - a DCQL query that lists every accepted credential type as an alternative (`web/src/lib/credentialTypes.ts`; today only the agent credential; a person credential would be one more entry in `credentials` and in `options`):
     ```json
     { "credentials": [ { "id": "agent", "format": "dc+sd-jwt",
         "meta": { "vct_values": ["https://registry.localtest.me/credentials/agent/v1"] },
         "claims": [ {"path": ["agent_id"]}, {"path": ["agent_name"]}, {"path": ["provider"]},
                     {"path": ["operator"]}, {"path": ["accreditation_level"]} ] } ],
       "credential_sets": [ { "options": [ ["agent"] ], "required": true } ] }
     ```
   - response mode `direct_post` (not `direct_post.jwt`: walt.id's conformance notes of 8 July 2026 show an audience bug there).
2. verifier2 signs request objects as `client_id = x509_san_dns:tickets.localtest.me`, with key and x5c from `infra/certs/verifier-*` and `urlPrefix = https://tickets.localtest.me/verification-session`.
   - The link is the by-reference form: `openid4vp://authorize?client_id=x509_san_dns%3Atickets.localtest.me&request_uri=https%3A%2F%2Ftickets.localtest.me%2Fverification-session%2F{id}%2Frequest&request_uri_method=post`. The `request_uri` answers both GET and POST (form `wallet_nonce`, `wallet_metadata`) with `Content-Type: application/oauth-authz-req+jwt`.
   - Request object header: `alg` ES256, `typ` `oauth-authz-req+jwt`, `x5c` = leaf only (SAN `tickets.localtest.me`, chains to `rootCA.pem`). Payload: `client_id`, `response_type` `vp_token`, `response_mode` `direct_post`, `response_uri` `.../{id}/response`, `nonce`, `state`, `dcql_query`, `client_metadata`, `aud` `https://self-issued.me/v2`, `iss` = client_id; no `exp`.
   - The wallet posts `vp_token={"agent":["<sd-jwt>~<disclosures>~<kb-jwt>"]}&state=...` to `response_uri`. The KB-JWT `aud` is the full client_id `x509_san_dns:tickets.localtest.me`; its `iat` must be within 5 minutes.
   - verifier2 policies: VC `signature`, `expiration`, `not-before`, `allowed-issuer` = `https://issuer.localtest.me/openid4vci`; VP `audience-check`, `kb-jwt_signature`, `nonce-check`, `sd_hash-check`, `kb-jwt_iat-check`, `exp-check`, `nbf-check`. verifier2 has no x5c-to-root policy for SD-JWT, so Lantern Tickets additionally checks the issuer x5c chain to `rootCA.pem`, its SAN and that its key is the one verifier2 verified with.
3. The verify page shows a QR code and the `openid4vp://...` link as an `<a>` with `id="verify-agent-link"` and visible text "Open in wallet". The agent reads it and calls the wallet's `present_credential` tool with it unchanged, then waits for the page to advance to Confirm and clicks **Hold my seats** (`id="hold-seats"`).
4. The Next.js server follows the session by polling `GET /verification-session/{id}/info`. On success it requires all requested claims (verifier2 reports SUCCESSFUL even when claims are withheld), checks the issuer chain, finds or creates the account, and the browser is signed in. Holding seats stores the disclosed claims on the order.

## Wallet MCP interface (A2WP as MCP tools)

Endpoint: `https://wallet.localtest.me/mcp`, MCP Streamable HTTP. Every request carries `Authorization: Bearer <agent API key>`. A missing or unknown key gets 401.

Tools. Every result carries an `operation` object in `structuredContent`, plus a one-line text summary. Exception (wallet): a request rejected before any operation exists (malformed URL, unknown `operation_id`, `wait_seconds` out of range, too many active operations) returns `isError: true` with `structuredContent` `{ "error": { "code", "detail" } }` and no `operation`. A refused `cancel_operation` returns `isError: true` with both `operation` and `error` (`not-cancellable`).

| Tool | Input | Notes |
| --- | --- | --- |
| `acquire_credential` | `{ "offer": string }`: an `openid-credential-offer://` URL or an `https` `credential_offer_uri` | Returns at once with the operation |
| `present_credential` | `{ "request": string }`: an `openid4vp://` URL (or `https` authorization request URL) | Returns at once with the operation |
| `get_operation` | `{ "operation_id": string, "wait_seconds"?: 0-25 }` | Long-polls up to `wait_seconds` for a status change |
| `cancel_operation` | `{ "operation_id": string }` | Before the commit point only |

Operation object:

```json
{ "operation_id": "op_...", "operation": "acquire" | "present",
  "status": "pending" | "processing" | "interaction_required" | "completed" | "failed" | "cancelled",
  "interaction": { "interaction_uri": "https://wallet.localtest.me/approve/...", "expires_at": "..." },
  "result": { "credentials": [ { "credential_handle": "...", "vct": "...", "issuer": "..." } ] },
  "error": { "code": "policy-denied" | "external-protocol-error" | "...", "detail": "..." } }
```

- `interaction` is present only while `interaction_required`. The agent forwards `interaction_uri` to the user; it never opens it itself.
- `result` is present only when `completed`; for `present`, it is `{ "verifier": "tickets.localtest.me" }`, plus `"redirect_uri"` if the verifier returned one.
- Error codes (wallet): the A2WP draft codes `invalid-request`, `unsupported-protocol`, `policy-denied`, `interaction-expired`, `operation-expired`, `external-protocol-error`, `outcome-unknown`, `not-cancellable`, plus `no-matching-credential` (acquire one first), `not-found` (unknown or another agent's operation) and `too-many-operations`.
- A repeated `acquire_credential` / `present_credential` with the same URL from the same agent returns the existing operation while it is nonterminal or completed, so a retried tool call cannot redeem or disclose twice.
- No credential, presentation or key ever appears in a tool result.

Configuration file (`wallet/config/wallet.json`, dev values):
- agents: `id`, `name`, `api_key_sha256`, `principal`, `policy`;
- policy: allowed issuers (`https://issuer.localtest.me/openid4vci`); allowed verifiers (`x509_san_dns:tickets.localtest.me`) with the claims each may receive; `require_approval`: `first_presentation_to_verifier`;
- trust anchors: `infra/certs/rootCA.pem`;
- `allowPrivateNetworks`;
- the public base URL (`https://wallet.localtest.me`) for approval links.

Dev API key for the demo agent: generated by `infra/scripts/dev-keys.sh`, never committed.

## Done means

- **wallet:** `./gradlew test` passes. With infra up, `acquire_credential` with a portal offer stores the credential, and `present_credential` with a checkout link completes, verified through verifier2. A README explains how to run it and how to point an MCP client at it.
- **web:** `pnpm build` passes. The portal creates real issuer2 offers, and checkout creates real verifier2 sessions and unlocks on success. A README explains how to run it.
- **Both:** note anything surprising about the A2WP model, the walt.id services or this contract in an "Observations" section of your own README. The orchestrator merges them into `docs/A2WP-DRAFT-FEEDBACK.md`.
