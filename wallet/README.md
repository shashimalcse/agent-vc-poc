# a2wp-kt

A Kotlin A2WP wallet exposed as a remote MCP server. It is the agent's wallet (approvals go to its owner): it holds the agent's own credentials and keys. The agent (OpenClaw) calls four tools over MCP Streamable HTTP. The wallet applies the policy the owner set, asks the owner for approval when needed, and runs OpenID4VCI and OpenID4VP against the issuer and verifier. Credentials, presentations and keys never reach the agent.

- **Issuance:** OpenID4VCI 1.0, pre-authorized code grant, optional `tx_code` entered on the wallet's approval page. Uses the EUDI library `eudi-lib-jvm-openid4vci-kt`.
- **Presentation:** OpenID4VP 1.0 with DCQL, signed request objects (`x509_san_dns` or `x509_hash`) and `direct_post`. Uses `eudi-lib-jvm-openid4vp-kt` and `eudi-lib-jvm-sdjwt-kt`.
- **Format:** SD-JWT VC (`dc+sd-jwt`) with key binding. Each credential gets a fresh P-256 holder key.

The contract with the other parts of the PoC is [`docs/WIRING.md`](../docs/WIRING.md).

## Build and test

Requires JDK 21. Gradle comes from the committed wrapper.

```sh
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-21.jdk/Contents/Home
./gradlew test          # unit tests (no network needed)
./gradlew installDist   # builds build/install/a2wp-kt
```

## Configure

1. Generate an API key for each agent. The script prints the key once, plus its SHA-256. Give the key to the agent, and put only the hash in the config.

   ```sh
   ../infra/scripts/dev-keys.sh openclaw
   ```

2. Copy the example and paste the hash into `agents[].api_key_sha256`:

   ```sh
   cp config/wallet.example.json config/wallet.json   # wallet.json is gitignored
   ```

Relative paths in the config resolve against the config file's directory. These are the main settings:

| Setting | Meaning |
| --- | --- |
| `listen` | Bind address. Default `127.0.0.1:8180`. Caddy proxies `https://wallet.localtest.me` to it. |
| `public_base_url` | Used to build approval links (`{base}/approve/{id}`). Must be `https`. |
| `allowed_hosts` | Accepted `Host` headers on `/mcp` (DNS-rebinding protection). |
| `trust_anchors` | PEM CA files. Used for outbound TLS, for the verifier's request-object `x5c` and for the issuer's credential `x5c`. In dev, this is `infra/certs/rootCA.pem`. |
| `trust_system_cas` | Also trust the JVM's CA set for TLS (never for `x5c`). Default `false`. |
| `allowPrivateNetworks` | Lets outbound calls reach loopback and private addresses. It must be `true` in dev because `*.localtest.me` resolves to 127.0.0.1. Link-local, multicast and `0.0.0.0/8` stay blocked either way. |
| `store` | Encrypted store file. The AES-256 key (base64) is read from the env var named by `key_env` (default `A2WP_STORE_KEY`), else from `key_file`, which is created with mode 600 if it is missing. |
| `limits` | Operation TTL (600 s), interaction TTL (300 s), retention, HTTP timeout and size limit, active operations per agent, and maximum input length. |
| `principals[]` | The agents' owners, who approve. `display_name` (e.g. "Ada") is optional and not shown in page headers. An optional `approval_passcode_sha256` makes the approval page ask for a passcode, so that holding the link is not enough to approve. |
| `agents[]` | `id`, `name`, `api_key_sha256`, `principal`, `policy`. |
| `policy.allowed_issuers[]` | `issuer`: the exact `credential_issuer` / `iss` string (here `https://issuer.localtest.me/openid4vci`). `vcts`: the accepted types. Anything not listed is denied. |
| `policy.allowed_verifiers[]` | `client_id`: the verified client_id with its prefix (`x509_san_dns:tickets.localtest.me`). `claims`: the top-level claims this verifier may receive. `name`: a label for the approval page. |
| `policy.require_approval` | `first_presentation_to_verifier` (the default), `always` or `never`. With the default, the user approves the first presentation to a verifier, and again whenever a later request asks for claims they haven't approved yet. |

## Run

```sh
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-21.jdk/Contents/Home
build/install/a2wp-kt/bin/a2wp-kt config/wallet.json
# or: ./gradlew run --args=config/wallet.json
```

`GET /healthz` returns `ok`. Logs contain operation ids, states, error codes, issuer and verifier ids. They never contain offers, credentials, presentations or keys.

## Connect an MCP client

Endpoint: `https://wallet.localtest.me/mcp` (Streamable HTTP). Every request needs `Authorization: Bearer <api key>`. A request without a valid key gets `401` before any MCP handling.

OpenClaw:

```sh
openclaw mcp add wallet \
  --url https://wallet.localtest.me/mcp \
  --transport streamable-http \
  --header "Authorization: Bearer $A2WP_API_KEY"
```

Check `openclaw mcp add --help` for the exact name of the header flag. The client must trust `infra/certs/rootCA.pem`, for example with `NODE_EXTRA_CA_CERTS=/path/to/rootCA.pem` for a Node-based client.

Raw check with curl:

```sh
curl --cacert ../infra/certs/rootCA.pem https://wallet.localtest.me/mcp \
  -H "Authorization: Bearer $A2WP_API_KEY" -H 'content-type: application/json' \
  -H 'accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
```

The transport is stateless: every POST is authenticated on its own, and the agent's identity comes from the API key, never from an MCP session id. `GET /mcp` (a server-to-client SSE stream) answers 405, which Streamable HTTP clients treat as "no stream".

### Tools

| Tool | Input | Result |
| --- | --- | --- |
| `acquire_credential` | `{ "offer": "openid-credential-offer://..." }` (or a bare https `credential_offer_uri`) | operation |
| `present_credential` | `{ "request": "openid4vp://..." }` (or an https authorization request URL) | operation |
| `get_operation` | `{ "operation_id": "op_...", "wait_seconds": 0-25 }` | operation; long-polls for a status change |
| `cancel_operation` | `{ "operation_id": "op_..." }` | cancelled operation, or `not-cancellable` |

Every result has `structuredContent.operation`, as in WIRING.md, and a one-line text that tells the agent what to do next. A request rejected before an operation exists returns `isError: true` and `structuredContent.error`. When the status is `interaction_required`, the agent sends `interaction.interaction_uri` to the user and keeps polling. It must not open the link itself.

### Operation model

- **States:** `pending → processing ⇄ interaction_required → completed | failed | cancelled`. Terminal states never change.
- **Commit point:** immediately before the wallet retains credentials (acquire) or posts the presentation (present). Cancelling is possible only before it. Expiry before the commit point gives `failed / operation-expired`; after it, `failed / outcome-unknown`. A failure during the presentation POST also gives `outcome-unknown`.
- **Retries:** the same offer or request URL from the same agent returns the existing operation while that operation is running or completed. A failed or cancelled one does not block a new attempt.
- **Before the commit point, acquire checks:** the issuer and `vct` are allowed, the issuer signature is valid (`x5c` to the trust anchor with a DNS SAN equal to the host of `iss`, or `jwt-vc-issuer` metadata), `iss` equals `credential_issuer`, `vct` is the offered type, `cnf.jwk` equals the fresh holder key, and the credential has not expired.
- **Present:** the EUDI library authenticates `client_id` from the signed request and its `x5c` chain to the trust anchor. Policy is checked against that verified client_id. The wallet discloses the claims that are both requested and allowed for that verifier. Non-selectively-disclosable claims, here `agent_id`, are always included. DCQL `credential_sets`: for each required set, the wallet uses the first option it can satisfy and skips optional sets. `claim_sets`: the wallet uses the first set whose claims are all allowed.

### Approval page

`https://wallet.localtest.me/approve/{id}` is server-rendered with no JavaScript. It follows the approved design in `docs/design/WalletApprove.dc.html` and `WalletDone.dc.html`: Registry colours, Geist and Geist Mono, mobile-first at 390 px wide, and centred on a grey ground on desktop. The fonts are bundled (`src/main/resources/assets/fonts`, SIL OFL licence alongside them) and served from `/assets/fonts/`, so the CSP stays `default-src 'none'; style-src 'unsafe-inline'; font-src 'self'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'`. Responses also carry `Cache-Control: no-store` and `Referrer-Policy: no-referrer`.

**Behaviour:**
- **GET has no side effects,** because chat apps prefetch links. Only POST approves or declines.
- **POST requests** from another `Origin` are refused.
- **The passcode,** when set, allows 5 attempts. After that, the request is declined.

**Approval screen for a presentation:**
- **Header:** "<agent name> · agent wallet", using the agent's configured name (e.g. "OpenClaw · agent wallet"). This is the agent's wallet (approvals go to its owner).
- **Title:** "Share with <name>?". The name is the owner's own config label for the *verified* client_id, or the verified host if there is none. The wallet can't verify what the verifier will do with the data, so it never shows a purpose.
- **Explanation:** "Your agent's wallet asks you before it shares the agent's credential with <name>."
- **Green "Verified requester" badge:** the host from the authenticated client_id. The full client_id is in its tooltip.
- **"WHAT WILL BE SHARED":** label and value rows (Agent ID, Agent name, Runs on, Operator, Accreditation), then the withheld claims, if any.
- **Footer:** "Only you can approve this. The wallet holds your agent's credential; the agent itself never sees it.", then the Decline and Approve buttons and the expiry time.

**Approval screen for a `tx_code` offer:** the same layout with the title "Add a credential from <issuer host>?" and the explanation "Your agent's wallet asks you before adding a credential from <issuer host>.", an "Allowed issuer" badge, the credential type and a code field. When a passcode is configured, the screen also has a passcode field.

**Result pages:**
- **Shared:** "Shared with <name>", with the blue credential card (agent name, "<n> details shared · HH:mm").
- **Credential added:** the same card.
- **Other states:**
  - Not shared / Not added (declined).
  - Expired.
  - Couldn't share / Couldn't add.
  - Not sure it arrived (`outcome-unknown`).
  - Cancelled.
  - Link not valid.

After Approve, the wallet waits up to 8 s for the operation to finish, so the page shows the real outcome, not an optimistic one. A later GET of the same link shows the same result. Every result page ends with "You can close this page."

## End-to-end check

With infra up and the wallet running, use a dedicated test agent's key (in dev, agent `integration`, key in `data/dev-agent-integration.txt`). Never use the demo agent's key: the script acquires real credentials, and they would belong to whichever agent ran it.

```sh
python3 -I scripts/integration.py "$A2WP_API_KEY"            # offer → acquire → verifier2 session → present
python3 -I scripts/integration.py "$A2WP_API_KEY" --tx-code  # same, with a tx_code entered on the approval page
python3 -I scripts/integration.py "$A2WP_API_KEY" --manual   # prints approval links for a person to open
```

The script creates the offer and the verifier session through the internal walt.id APIs, the same way the portal and checkout do. It then plays the user on the approval page and checks that verifier2 reports `SUCCESSFUL`.

## Store admin and reset for demo takes

```sh
build/install/a2wp-kt/bin/a2wpctl list config/wallet.json   # credentials per agent (handle, agent, agent_id/agent_name, vct, issuer) and approvals; never secrets
scripts/reset.sh                                             # back up data/wallet.store to data/backups/, then wipe it
```

`reset.sh` refuses to run while the wallet is listening. Stop the wallet, reset, then start it again. The reset removes all credentials, holder keys and approvals. API keys, the store key and the config stay. Backups stay encrypted with the same store key; to restore one, copy it back to `data/wallet.store` while the wallet is stopped.

## Credential ownership

This is the agent's wallet (approvals go to its owner). A credential and its holder key belong to the agent whose API key acquired it, and `present_credential` only ever considers that agent's credentials. Two agents of the same principal cannot use each other's credentials. The principal's only role is to approve, through the approval page and the optional passcode. Approval memory is kept per (agent, verifier) together with the approved claim set. When several of an agent's credentials match a request, the wallet uses the most recently acquired one.

## Observations

These are findings for the A2WP draft, from running this wallet against walt.id issuer2/verifier2 1.1.1 on 12 Oct 2026. They come from the wallet side and a scripted agent. OpenClaw behaviour is not observed yet.

- **Operation model over MCP:** without approval, acquire finished in about 0.8 s, and present about 0.1 s after the user approved, so a blocking tool call would have been enough. The asynchronous model only paid off for `interaction_required`, where the user takes minutes, well past typical tool-call timeouts.
- **Long polling made the model cheap:** with `get_operation(wait_seconds=25)`, one poll after creation always reached the next meaningful state.
- **`pending` vs `processing` made no difference:** the first result showed `pending` or `processing` depending on thread scheduling, and the agent's next step is identical ("poll"). This supports merging them.
- **`status_uri` has no use over MCP:** `operation_id` plus the `get_operation` tool did the job.
- **`completed_at` and `expires_at` were never needed,** so the wallet doesn't return them. `interaction.expires_at` is useful because the agent can tell the user how long the link lasts.
- **`context` was never needed:** nothing in either flow wanted it, and the wallet doesn't accept it.
- **Query and credential handles were never needed:** the whole checkout ran without query. `credential_handle` appears only in the acquire result and is never an input.
- **Credentials belong to an agent, not to the principal:** in an end-to-end run, the demo agent presented a stale "Integration agent" credential. My test script had acquired it with the demo agent's own API key. Scoping was per agent all along, so it was not another agent's credential leaking. Even so, the draft never says whose credentials an operation may use. It should say that the credential and holder key belong to the authenticated agent, and that the principal only approves (§2–3 Principal context).
- **Several matching credentials:** when an agent holds several credentials that match, the wallet silently uses the most recently acquired one rather than asking the owner. A re-registered agent's old credential is otherwise indistinguishable from the new one, and asking would add a step to every checkout. The draft leaves selection to the wallet (§5.1). In practice, either the wallet stops keeping superseded credentials of the same type and issuer, or the approval page shows which one it chose (it shows the agent_id and agent_name).
- **Idempotency needs no new parameter:** the offer URL and the `request_uri` already identify the transaction. Deduplicating per agent on that URL stops a retried tool call from redeeming or disclosing twice. A separate idempotency key (draft §5.4) was unnecessary here.
- **Taking URLs as-is was simpler than the draft's request members:** the EUDI VP library resolves the `openid4vp://` URL directly. Splitting it into outer parameters (draft §7.2) would only have been re-encoded.
- **Bare `credential_offer_uri`:** the EUDI VCI library wants the `openid-credential-offer://` form, so the wallet wraps a bare https URI.
- **The issuer is unknown until the offer is fetched:** a by-reference offer doesn't name its issuer until it is fetched. The wallet therefore fetches the offer and the metadata (reads only, through the SSRF guard) before the issuer policy check. The pre-authorized code is redeemed only after the check. The draft should say that issuer authorization happens before redemption, not before any fetch.
- **The issuer identifier is an exact string:** issuer2 makes `credential_issuer` = `iss` = `https://issuer.localtest.me/openid4vci`, not the host. Policy has to match the exact identifier, while `x5c` trust can only bind the host (via the SAN).
- **`tx_code` on the approval page works:** with a walt.id offer that required a 6-digit code, the code was entered on the wallet page and the agent never saw it. Even so, the approval link is a bearer capability, and an agent that opens it could approve itself. The optional principal passcode closes that. The draft should require the interaction to authenticate the user, not just check possession of the URI (§4.3).
- **Link prefetch:** chat apps prefetch links, so the interaction URI's GET must be side-effect free. The draft doesn't say so.
- **Re-approval:** "first presentation to this verifier" isn't enough on its own. A later request for more claims needs approval again (§10.1 "material changes"). The wallet stores the approved claim set per (agent, verifier).
- **Verifier identity:** the verified identity is `x509_san_dns:tickets.localtest.me`, a hostname. The friendly name "Lantern Tickets" exists only in `client_metadata.client_name`, which is unverified. The approval page shows the verified client_id and certificate subject, plus the name from the wallet's own config. It doesn't show `client_name`.
- **Minimal disclosure is invisible to verifier2:** with the policy restricted to `agent_id` and `provider`, verifier2 still reported `SUCCESSFUL` even though the DCQL query asked for five claims. The relying party has to check which claims arrived. This backs the draft's statement that completion is not acceptance (§5.2).
- **Declining doesn't notify the verifier:** the wallet sends no error response, so the checkout waits until its session expires. Sending `access_denied` to an authenticated, allowed verifier would be friendlier. The draft could say whether a declined present should notify the verifier.
- **The commit point sits before the POST:** the EUDI dispatcher parses the verifier's response body after the POST. A 2xx response with a non-JSON body would therefore surface as an exception after the commit point, and so as `outcome-unknown`, though it didn't happen with verifier2. That is the right conservative answer, and an example of a failure that doesn't imply absence of effects.
- **Error codes and next actions:**
  - `outcome-unknown`: don't retry, ask the user.
  - `no-matching-credential` (added): acquire first.
  - `policy-denied`: stop.
  - `interaction-expired` / `operation-expired`: ask the user whether to retry.
  - `invalid-request`: fix the input.
  - `not-found` (added): stop polling.
  - Never needed: `unsupported-operation` (each operation is its own tool), and `not-authorized` (another agent's operation looks like `not-found`, as §8.1 allows).
- **Request errors in the MCP binding:** they are tool results with `isError` and no operation. MCP has no HTTP status channel for them, so the draft's 400/403 mapping (§9) has no MCP equivalent.
- **Access protection:** a static per-agent Bearer key and a stateless MCP transport were enough. The key, not the MCP session id, establishes the agent and principal context. A stateful MCP session would have to be bound to the key, because session ids are not credentials.
- **`request_uri_method=post`:** walt.id links include it. The wallet supports GET only, so it doesn't send wallet metadata or a `wallet_nonce`, and the EUDI library falls back to GET. verifier2 accepted this.
- **verifier2 retains everything:** `/info` returns the full `vp_token` (all disclosed values and the holder key) and keeps sessions until 2036 by default. This is outside A2WP, but it limits what minimal disclosure buys.
- **EUDI library notes:**
  - `ProofsConfig.Default` doesn't enable plain JWT proofs (without key attestation), so issuer2 needed an explicit `jwtProofsWithoutKeyAttestation`.
  - The Nimbus SD-JWT ops have no `unverifiedIssuanceFrom`, so the wallet uses `DefaultSdJwtOps` for stored credentials.
  - The SD-JWT VC verifier requires `typ` = `dc+sd-jwt`, which issuer2 sends.
- **WIRING.md:**
  - The issuer identifier was initially the bare host. It was corrected to `/openid4vci`.
  - "every result carries an operation" needed an exception for request errors.
  - The present `result` can carry `redirect_uri`, but none was returned in the cross-device flow.
