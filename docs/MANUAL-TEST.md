# Manual test

You play the user in the browser. `scripts/agent.py` plays the personal agent: it hands links to the wallet over MCP and passes approval links back to you. It never approves anything. Run commands from the repo root.

## Before you start

- Services: Caddy, issuer2 and verifier2 run in Docker; the web app runs on :3100 and the wallet on :8180. Check them with `docker compose -f infra/docker-compose.yml ps` and `lsof -nP -iTCP:3100,8180 -sTCP:LISTEN`.
- Browser trust, once: `CAROOT=$PWD/infra/certs mkcert -install` makes Chrome and Safari trust the `*.localtest.me` certificates. Firefox needs `nss` installed first. Without it, accept the warning on each of `registry`, `tickets` and `wallet`.
- Wallet agents: `manual` (default for `agent.py`, fresh, so you'll see the approval page) and `openclaw` (already approved Lantern Tickets). Both belong to the same user.

## 1. Issue the agent credential

1. Open https://registry.localtest.me, sign in with any display name, and register an agent.
2. On the agent's page, choose **Issue a new agent credential offer**, and copy the `openid-credential-offer://` link (Copy button).
3. Hand it to the agent:

   ```
   python3 -I scripts/agent.py acquire '<offer link>'
   ```

4. Expect `completed` with one credential. Reload the portal: the status is **Issued**.

## 2. Verify your agent at checkout

1. Open https://tickets.localtest.me, pick up to 4 seats, and continue to checkout.
2. Choose **Verify your agent**. Copy the `openid4vp://` link: right-click → Copy Link, since clicking it tries to open a wallet app.
3. Hand it to the agent:

   ```
   python3 -I scripts/agent.py present '<verify link>'
   ```

4. The agent prints an approval link. Open it, check who is asking and what will be shared, then **Approve**.
5. The terminal shows `completed`, and the checkout page changes to **Seats held**.

## Things worth trying

Each one feeds `docs/A2WP-DRAFT-FEEDBACK.md`.

- **Decline** on the approval page. The checkout keeps waiting until its session expires (item 18).
- **Present again** with the same agent. There is no approval the second time, as long as the requested claims are unchanged.
- **Hand the same offer link over twice.** The wallet returns the existing operation instead of redeeming twice (item 8).
- **Present before acquiring with this agent** (`--agent manual` on a fresh portal agent). Credentials are scoped to the user, so watch whether one agent can present a credential issued to the other (item 2).
- **Buy past the cap.** More than 4 tickets for the same agent is refused.
- **An offer that requires a PIN** (`tx_code`): the wallet's approval page asks you for it, never the agent. The portal doesn't create these yet; `wallet/scripts/integration.py --tx-code` does.

## When something breaks

- Wallet log: `wallet/data/wallet.log`.
- walt.id logs: `docker compose -f infra/docker-compose.yml logs issuer-api2 verifier-api2`.
- Restarting issuer2 or verifier2 drops open offers and checkout sessions, which are kept in memory; create new ones.
