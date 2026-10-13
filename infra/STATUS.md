# Infra status

Newest first. Maintained by the orchestrator.

## 2026-10-12 22:40 — Caddy reaches the Mac host

On Rancher Desktop, `extra_hosts: host.docker.internal:host-gateway` pointed at the Lima VM's Docker bridge (172.17.0.1), not the Mac (found by the web agent). I removed it: Rancher Desktop's own `host.docker.internal` resolves to 192.168.5.2 (the Mac). Verified: `registry` and `tickets` return 200 through Caddy from Next.js on :3100; `wallet` returns 502 until the wallet runs on :8180.

## 2026-10-12 22:30 — walt.id up; management APIs locked down

- **issuer2 and verifier2 are running** with the web agent's config: issuer2 on `127.0.0.1:7005`, verifier2 on `127.0.0.1:7004`.
- **Security fix.** issuer2's management API (`/issuer2/*`) has no auth, and `/issuer2/profiles` returns the issuer's private signing JWK.
  - `issuer.localtest.me` now proxies only `/.well-known/*`, `/openid4vci/*` and `/static/*`, and returns 404 for everything else.
  - Both walt.id ports are bound to 127.0.0.1 instead of all interfaces.
  - Verified: `/issuer2/profiles` → 404 publicly; `/.well-known/openid-credential-issuer/openid4vci` → 200.
- **Issuer identifier:** `credential_issuer` = `iss` = `https://issuer.localtest.me/openid4vci` (confirmed by the web agent).

## 2026-10-12 22:10 — Port change

Ports 3000 and 8080 belong to another project on this Mac. Next.js runs on **3100** and the wallet on **8180**. Caddy and `docs/WIRING.md` are updated.

## 2026-10-12 22:05 — Caddy up, certs ready, walt.id images pulled

- **Caddy** is running on host port 443 with TLS from the local mkcert CA. It routes as in `docs/WIRING.md`.
  - Check: `curl --cacert infra/certs/rootCA.pem https://tickets.localtest.me/`
  - issuer2 and the wallet return 502 until those services run.
- **Certificates** are in `infra/certs/`:
  - `rootCA.pem`: the trust anchor for TLS, the issuer's x5c and the verifier's x5c. Never install it system-wide from code.
  - `tls.pem` / `tls-key.pem`: the Caddy certificate for all four hosts.
  - `issuer-signing.pem` / `-key.pem`: P-256, SAN `issuer.localtest.me`. JWK in `issuer-signing.jwk.json` (private, kid `oaZ1DtfQD0XW4HNr9C7BQewMe7inovFvh8vkF56lkqU`), `issuer-signing.public.jwk.json`; x5c (leaf, root) in `issuer-signing.x5c.json`.
  - `verifier-signing.pem` / `-key.pem`: P-256, SAN `tickets.localtest.me`. JWK in `verifier-signing.jwk.json` (kid `JJUhbApG9P3ALqdf97zQ_O1IplB_ZuqoToUfo0rx_2M`), `verifier-signing.public.jwk.json`, `verifier-signing.x5c.json`.
  - Regenerate the JWK files with `node infra/scripts/derive-jwks.mjs`.
  - The walt.id containers mount `infra/certs` read-only at `/certs`.
- **walt.id images** `waltid/issuer-api2:1.1.1` and `waltid/verifier-api2:1.1.1` are pulled. They are in the Compose profile `waltid` and mount `infra/waltid/issuer2/config` and `infra/waltid/verifier2/config`.
  - Not started yet: those config folders don't exist yet (owned by the `web` agent).
  - Start or restart: `docker compose -f infra/docker-compose.yml --profile waltid up -d` (any agent may run this once the config exists).
  - Ports: issuer2 `localhost:7005`, verifier2 `localhost:7004`.
- **Wallet API keys:** `infra/scripts/dev-keys.sh <agent_id>` prints a key and its SHA-256.
