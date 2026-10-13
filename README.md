# Agent credential PoC

A personal agent gets its own verifiable credential and uses it to get past a "verify your agent" checkout step. The PoC also tests the A2WP draft. Design: [PoC design doc](https://claude.ai/code/artifact/7fc6fb09-45f5-457c-97d4-f1a9e0d2b2e9). Contract between parts: [docs/WIRING.md](docs/WIRING.md). Draft findings: [docs/A2WP-DRAFT-FEEDBACK.md](docs/A2WP-DRAFT-FEEDBACK.md).

| Part | Path | What it is |
| --- | --- | --- |
| Wallet | `wallet/` | `a2wp-kt`: Kotlin A2WP wallet on the EUDI libraries, served as a remote MCP server |
| Web | `web/` | Next.js: the Agent Registry portal (issues agent credentials) and Lantern Tickets (verifies agents at checkout) |
| Infra | `infra/` | Caddy (TLS for `*.localtest.me`), walt.id `issuer-api2` and `verifier-api2`, a local CA |
| Agent VM | `agent-vm/` | Lima VM with OpenClaw (later) |

## Run it locally

Prerequisites: Docker, JDK 21, Node and pnpm, mkcert.

1. Start Caddy and walt.id:

   ```bash
   docker compose -f infra/docker-compose.yml --profile waltid up -d
   ```

2. Start the web app on port 3100. See `web/README.md`.
3. Create an agent API key with `infra/scripts/dev-keys.sh demo-agent`, put its hash in `wallet/config/wallet.json`, and start the wallet on port 8180. See `wallet/README.md`.
4. Open `https://registry.localtest.me` and `https://tickets.localtest.me`. Your browser trusts these only if you trust `infra/certs/rootCA.pem` yourself, for example with `CAROOT=infra/certs mkcert -install`.

Ports 3000 and 8080 are deliberately avoided; another project on this machine uses them.
