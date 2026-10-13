# A2WP draft feedback from the agent-credential PoC

Draft: [draft-senarath-a2wp-00](https://www.ietf.org/archive/id/draft-senarath-a2wp-00.html)
Started: 12 Oct 2026
PoC design: [Agent Credentials for Personal Agents: PoC Design](https://claude.ai/code/artifact/7fc6fb09-45f5-457c-97d4-f1a9e0d2b2e9)

The PoC tests -00 against a real personal agent (OpenClaw), a remote MCP wallet built on the EUDI libraries, and walt.id's issuer and verifier. Nothing in -00 is assumed correct. Each item below is a hypothesis with a starting leaning. Evidence from the PoC sets the final call, and a "keep" has to be earned the same way as a "drop".

## Direction so far

Updated 12 Oct 2026 after the first end-to-end run: the real portal offer, then the a2wp-kt wallet over MCP, then the real checkout link, then seats held. The agent was scripted; OpenClaw itself has not been tried yet.

| Leaning | Items |
| --- | --- |
| Drop | §4.1 `context` (never used); §4.4 + §6 query and handles (never used); §4.2 `status_uri` and `completed_at` (no use over MCP) |
| Change | §2–3 credentials scoped per agent, the principal only approves (item 2); §1.1 scope; §5.2 merge `pending` and `processing`; §5.4 deduplicate on the external transaction instead of a new key; §7.1 `tx_code` via the wallet page; §7.1 and §7.2 take URLs as-is; §7.2 completion; §9 errors; §4.3 the interaction must authenticate the user |
| Add | §7.1 issuer authorization before redemption; §10 relay security considerations; declined presentations notify the verifier (item 18); an MCP binding (item 19) |
| Open |  §8.2 access protection (a static key was enough locally); §10.1 counterparty (the verified identity is only a hostname) |

## Items

### 1. §1.1 Scope: the Agent as Holder

- **Problem.** The draft excludes how "a credential proves the identity of an Agent", yet the PoC's core case is exactly that: the Agent is Holder and subject of its own credential.
- **PoC test.** The agent's own credential unlocks checkout at the ticketing site.
- **Leaning.** Change.
- **Candidate change.** State that the Agent can be the Holder and subject, and that this is in scope.

### 2. §2–3 Principal context

- **Problem.** "Principal context" is required for authorization but left undefined. Does it carry weight when one wallet serves several agents for one user, holding both the agents' and the user's credentials?
- **PoC test.** One wallet and two agents with separate API keys and policies.
- **Evidence.** With credentials scoped per principal, one agent presented a credential that had been collected for another agent. The wallet is the agent's own, so its credentials and keys belong to that agent; the principal only approves.
- **Leaning.** Change (was Open).
- **Candidate change.** Define the operation's context as the Agent. Credentials and keys are scoped to the Agent that acquired them, and the Principal is the approving party. Say how each binding establishes both.

### 3. §4.1 `context`

- **Problem.** Advisory context authorizes nothing and has no defined members.
- **PoC test.** Log whether OpenClaw or the wallet ever reads it.
- **Leaning.** Drop.
- **Candidate change.** Remove it from the core.

### 4. §4.2 Operation representation

- **Problem.** `status_uri` duplicates `Location`, and an MCP binding has neither. It's unclear anyone reads `completed_at` or `expires_at`.
- **PoC test.** Record which members the agent actually uses.
- **Leaning.** Change.
- **Candidate change.** Trim to the members agents use, and move binding-specific members into the binding.

### 5. §4.3 Interaction

- **Problem.** A wallet with no public listener can't serve an `interaction_uri`. A remote wallet can, but the URI has to reach the user through the agent.
- **PoC test.** An approval page on the wallet. OpenClaw sends the link over Telegram. Approval is required for a first presentation to a new verifier.
- **Evidence.**
    - The approval page and `tx_code` entry worked.
    - The link is a bearer capability, so an agent that opens it could approve itself.
    - Chat apps prefetch links, so GET must be side-effect free.
    - A later request for more claims needs fresh approval.
    - `interaction.expires_at` was useful to the agent.
    - Whether to ask the user at all is wallet policy, not protocol. An agent credential exists so the agent can act on its own: the PoC's repeat presentations (same agent, verifier and claims) completed with no interaction. Wallet implementers will still demand user interaction for some tasks, for example a first presentation to a new verifier, wider disclosure, an unknown issuer, or `tx_code`.
- **Leaning.** Change (was Open).
- **Candidate change.** Keep `interaction_uri`, but require that the interaction authenticates the user, not mere possession of the URI. Require a side-effect-free GET, and renewed approval when the requested disclosure grows. State that interaction is optional and wallet-policy driven: an operation may complete autonomously, and the agent must handle both outcomes without knowing the policy in advance.

### 6. §4.4 + §6 Query and handles

- **Problem.** Handles can't select anything, and filter vocabulary is deployment-defined, so query has little interoperable value.
- **PoC test.** Run the full checkout without query.
- **Leaning.** Drop.
- **Candidate change.** Move query and handles to an extension.

### 7. §5.2 Status set

- **Problem.** Does an agent behave differently on `pending` than on `processing`?
- **PoC test.** Watch the MCP tool calls and the LLM's behaviour in each state.
- **Evidence.** The first result showed `pending` or `processing` depending on thread scheduling, and the agent's next step was identical either way.
- **Leaning.** Change (was Open).
- **Candidate change.** Possibly merge the two.

### 8. §5.4 Retries

- **Problem.** LLM tool calls get retried, and a duplicate `present` can disclose twice. -00 only forbids automatic retries.
- **PoC test.** Inject failures between the agent and the wallet.
- **Evidence.** The offer URL and the `request_uri` already identify the external transaction. Deduplicating per agent on that URL made retries safe without any new parameter.
- **Leaning.** Change (was Add).
- **Candidate change.** Require the wallet to treat a repeated request for the same external transaction from the same agent as the existing operation, instead of adding an idempotency key.

### 9. §7.1 `tx_code`

- **Problem.** Who enters a transaction code when the wallet is headless? -00 gives no way to deliver it.
- **Why it matters here.** In the issuance story, the user creates a pre-authorized offer at the accreditor portal and pastes the link to the agent in chat. Until it's redeemed, that link is a bearer secret: whoever redeems it first gets the credential bound to their own key, and chat history keeps the link. `tx_code` is the standard defence.
- **PoC test.** A walt.id offer that requires `tx_code`. The portal shows the PIN to the user.
- **Leaning.** Change.
- **Candidate change.** Collect the code through the §4.3 approval page. Never let the agent relay it.

### 10. §7.1 and §7.2 Request form

- **Problem.** Agents receive URLs: an `openid-credential-offer://` link pasted in chat, and an `openid4vp://` link read off a page. -00 makes them split these into request members (`credential_offer` / `credential_offer_uri`, and the outer authorization request parameters).
- **PoC test.** Support both forms for `acquire` and `present`, and compare the failure cases.
- **Leaning.** Change.
- **Candidate change.** Accept the offer URL and the authorization request URL as-is.

### 11. §7.2 Completion and redirect

- **Problem.** A headless wallet can't "initiate user-agent redirection", and the mapping returns no result.
- **PoC test.** The checkout step is cross-device: the site follows the verifier2 session. Also try a same-device session that returns `redirect_uri`.
- **Evidence.** In the cross-device checkout no redirect was needed: the page polled and moved on. The wallet returns `redirect_uri` in the result when a verifier sends one. The same-device case is still untested.
- **Leaning.** Change.
- **Candidate change.** Return the redirect target in the result, or drop the requirement for headless wallets.

### 12. §8 Binding

- **Problem.** Agents speak MCP. Is MCP over Streamable HTTP the binding, which would make the -00 HTTPS binding redundant?
- **PoC test.** Remote MCP as the only binding. Record anything that had to be agreed outside the draft (tool names, schemas, polling). Optionally compare with a local Unix socket behind a stdio shim.
- **Evidence.**
    - Four tools covered everything: `acquire_credential`, `present_credential`, `get_operation` with `wait_seconds` long-polling, and `cancel_operation`.
    - Things that had to be agreed outside -00: tool names, input names, long-polling, server instructions to the agent, and how request errors appear (MCP has no HTTP status, so `isError` results take the place of §9's 400/403).
- **Leaning.** Change (was Open); see item 19.
- **Candidate change.** Define A2WP as MCP tools, and drop the custom HTTPS binding.

### 13. §8.2 Access protection

- **Problem.** A static per-agent API key is enough for the PoC. Does the core need an OAuth profile? MCP authorization uses Bearer tokens, while -00 recommends DPoP.
- **PoC test.** API keys now, OAuth later. Record what each one needs and protects.
- **Leaning.** Open.
- **Candidate change.** Access protection as a profile, not core. Settle Bearer vs DPoP.

### 14. §9 Errors

- **Problem.** There are ten codes; it's unclear which ones change what an agent does next.
- **PoC test.** Map each code to the agent's next action.
- **Evidence.**
    - `outcome-unknown` → don't retry; ask the user.
    - `policy-denied` → stop.
    - `*-expired` → ask whether to retry.
    - `invalid-request` → fix the input.
    - Added codes: `no-matching-credential` (acquire first), `not-found` (stop polling), `too-many-operations`.
    - Never needed: `unsupported-operation` (each operation is its own tool) and `not-authorized` (another agent's operation looks like `not-found`).
- **Leaning.** Change.
- **Candidate change.** Keep only codes that change behaviour.

### 15. §10.1 Counterparty

- **Problem.** Whose identity does the request carry, the company's or the verifier service's? verifier2 can sign as the company (`x509_san_dns:<company host>`) or as a shared service.
- **PoC test.** Run both. See what wallet policy and the approval page can show the user.
- **Evidence.** With verifier2 signing as the company, the verified identity is just `x509_san_dns:tickets.localtest.me`. The friendly name exists only in the unverified `client_metadata.client_name`, so the wallet shows the hostname, the certificate subject and a name from its own config.
- **Leaning.** Open.
- **Candidate change.** Say how a wallet identifies the relying party behind a hosted verifier.

### 16. §10 Relay of cross-device requests

- **Problem.** An agent forwards whatever request a page shows. A malicious page can display a request lifted from a real checkout, so the presentation unlocks the attacker's checkout.
- **PoC test.** Replay a captured request from a second browser. See what the wallet, the verifier and the agent can detect.
- **Evidence so far.** verifier2's short link (`client_id` + `request_uri`) is all a presenter needs, and nothing binds it to the browser that displayed it. The replay test itself is still to run.
- **Leaning.** Add.
- **Candidate change.** Security considerations for agent-relayed cross-device requests.

### 17. §7.1 / §10.1 Offers from untrusted sources

- **Problem.** An agent can receive a credential offer from anywhere: the user in chat, or a web page it is browsing. -00 asks the wallet to authorize the actual counterparty, but says nothing specific about acquisition. A wallet that accepts any offer fills up with credentials it may later present.
- **PoC test.** Wallet policy allows only Agent Registry to issue agent credentials. Feed the agent an offer from another issuer through a web page, and check that the wallet refuses it and what the agent sees.
- **Evidence.** A by-reference offer doesn't name its issuer until it is fetched. The wallet had to fetch the offer and metadata (reads only, through the SSRF guard) before the policy check, and redeemed the code only after it.
- **Leaning.** Add.
- **Candidate change.** State that acquisition requires issuer authorization before the pre-authorized code is redeemed (not before any fetch), and consider requiring user approval for an issuer the wallet hasn't seen before.

### 18. Declined presentations

- **Problem.** When the user declines on the approval page, -00 doesn't say whether the wallet tells the verifier.
- **Evidence.** The wallet sent nothing, so the checkout waited until its session expired.
- **Leaning.** Add.
- **Candidate change.** For an authenticated, policy-allowed verifier, send an OpenID4VP error response (`access_denied`) when the user declines. Keep silence for unknown or denied verifiers.

### 19. Operation model and the MCP binding

- **Problem.** -00's asynchronous operation model and HTTPS binding assume a REST client. Agents call tools.
- **Evidence.**
    - Without approval, acquire finished in about 0.8 s, so a blocking tool call would have been enough.
    - The operation model only mattered for `interaction_required`, where the user takes minutes.
    - Long-polling `get_operation` with `wait_seconds=25` reached the next meaningful state in one call every time.
- **Leaning.** Add.
- **Candidate change.** Define an MCP binding: one tool per operation, an operation object only where interaction can happen, and long-polling for status. Make the HTTPS binding optional.

## Earlier notes

- **§4 Duplicate members.** Many JSON libraries silently keep the last duplicate. A reference to I-JSON (RFC 7493), or a note, would help implementers see they need a validating pass.
- **§8.1 vs §9 Scope failures on retrieval.** It is unclear whether reading an operation without the matching scope gets 403 `not-authorized` or 404.
- **§6 / §10.3 Inventory probing.** An agent allowed to present can learn which credentials exist by sending crafted presentation requests and watching success or failure. Query authorization alone doesn't prevent this.

## Evidence log

Fill this in while the PoC runs, newest first.

| Date | Item | Observation | Effect on leaning |
| --- | --- | --- | --- |
| 2026-10-13 | 2 | The wallet scoped credentials per principal: agent `openclaw` presented a credential collected for another agent (a test agent), and `manual`'s credential sat in the same pool. With the wallet as the agent's own, credentials and keys must be scoped per agent; the principal only approves. -00 never says whose credentials an operation may use. | Open → Change: define per-agent credential scope |
| 2026-10-13 | All | Redesigned pages end to end with the real wallet: Registry offer flips to Issued live; Lantern Sign in, then Open in wallet, then present (no approval: same agent, verifier and claims as before), then auto-advance to Welcome, then Hold my seats, then Seats held | Baseline for the video flow |
| 2026-10-12 | All | First real end-to-end run: portal offer, then `acquire_credential` (completed), then the checkout link, then `present_credential` (completed, no re-approval needed), then "Seats held". Scripted agent; OpenClaw not yet tried. | Baseline |
| 2026-10-12 | 3, 6 | `context`, query and handles were never needed in either flow | Drop confirmed |
| 2026-10-12 | 4 | `status_uri` has no use over MCP; `completed_at` and `expires_at` unused; `interaction.expires_at` useful | Change confirmed |
| 2026-10-12 | 7 | `pending` vs `processing` varied with scheduling; same agent action | Open → Change |
| 2026-10-12 | 8 | Deduplicating on the offer or request URL made retries safe | Add → Change |
| 2026-10-12 | 9 | `tx_code` entered on the wallet page; the agent never saw it. The offer URL keeps answering 200 after redemption, but the code is single-use | Change confirmed |
| 2026-10-12 | 10 | EUDI resolved the `openid4vp://` URL directly. walt.id links carry `request_uri_method=post`, which a split-members form could easily drop | Change confirmed |
| 2026-10-12 | 5 | The approval link is a bearer capability; chat-app prefetch needs a safe GET; re-approval is needed when claims grow | Open → Change |
| 2026-10-12 | 15 | The verified verifier identity is a hostname; the friendly name is unverified | Still open |
| 2026-10-12 | 17 | The issuer is unknown until the by-reference offer is fetched | Add, refined |
| 2026-10-12 | 18 | A declined presentation leaves the verifier waiting until expiry | New item |
| 2026-10-12 | 19 | Async was only needed for interaction; long-polling made it cheap | New item |
| 2026-10-12 | §5.2 | verifier2 reported SUCCESSFUL although requested claims were withheld; the shop checks claims itself | Supports "completion is not acceptance" |
