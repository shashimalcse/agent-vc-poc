# walt.id config changes

Newest first. Owner: `web` agent. Images: `waltid/issuer-api2:1.1.1`, `waltid/verifier-api2:1.1.1`.
Base: walt.id `docker-compose/issuer-api2/config` and `docker-compose/verifier-api2/config` at tag `v1.1.1`.

## 2026-10-12 22:30: issuer signing key rotated (both containers restarted)

- The issuer key was readable at `https://issuer.localtest.me/issuer2/profiles` (issuer2's unauthenticated management API returns the profile's private `issuerKey`) until the orchestrator restricted Caddy and bound 7004/7005 to 127.0.0.1. So it was rotated:
  `CAROOT=infra/certs mkcert -ecdsa -cert-file infra/certs/issuer-signing.pem -key-file infra/certs/issuer-signing-key.pem issuer.localtest.me`, then `node infra/scripts/derive-jwks.mjs`, then `node infra/waltid/sync-certs.mjs`. The new kid is `6843S6rsB0SuZBIhf9yv0To7aLRkX7sCzrZFaU2HqNA`. The root CA is unchanged, so wallets need no change.
- Added `infra/waltid/sync-certs.mjs`. It copies the public leaf certificates from `infra/certs` into the generated blocks of `issuer2-profiles.conf` and `verifier-service.conf`. Private keys are never copied: the config includes them from the read-only `/certs` mount.
- **Pre-demo step:** if any key may have been exposed again, repeat the commands above (for the verifier, use `tickets.localtest.me` and `verifier-signing*`), then run `docker compose -f infra/docker-compose.yml --profile waltid restart issuer-api2 verifier-api2`.

## 2026-10-12 22:10: issuer2 allows anonymous pre-authorized access (issuer2 restarted)

- `issuer2/config/issuer-service.conf`: added `clientAuthenticationConfig.supportedMethods = [{ type = "preauth-anonymous" }]`. Without it, issuer2 1.1.1 rejects a pre-authorized token request that has no `client_id` with `401 invalid_client` ("Anonymous pre-authorized code access is not supported").

## 2026-10-12 22:06: initial config (both containers started)

issuer2 (`infra/waltid/issuer2/config`):
- `issuer-service.conf`: `baseUrl = "https://issuer.localtest.me"`. issuer2 appends `/openid4vci`, so `credential_issuer` and the SD-JWT `iss` are `https://issuer.localtest.me/openid4vci`. walt.id's demo `ciTokenKey` is kept; it signs access tokens only, and is dev-only.
- `credential-issuer-metadata.conf`: replaces the demo catalogue with one configuration, `AgentCredential`. It is `dc+sd-jwt` with vct `https://registry.localtest.me/credentials/agent/v1`, ES256, binding `jwk` and a `jwt` proof with ES256, plus display and claims metadata.
- `issuer2-profiles.conf`: one profile, `AgentCredential`. The issuer key is included from `/certs/issuer-signing.jwk.json`, and `x5Chain` is the issuer leaf PEM. `selectiveDisclosure` leaves `agent_id` plain and makes `agent_name`, `provider`, `operator` and `accreditation_level` selectively disclosable, with no decoys. Mapping: `iat`, `nbf` and `exp = <timestamp-in-seconds:90d>`. The portal supplies the claim values per offer via `runtimeOverrides.credentialData`.
- `_features.conf`, `authentication-service.conf`, `dev-mode.conf`, `persistence.conf` (in-memory) and `web.conf` are unchanged.

verifier2 (`infra/waltid/verifier2/config`):
- `verifier-service.conf`: `clientId = "x509_san_dns:tickets.localtest.me"`, `clientMetadata.client_name = "Lantern Tickets"`, `urlPrefix = "https://tickets.localtest.me/verification-session"`, `urlHost = "openid4vp://authorize"`. The key is included from `/certs/verifier-signing.jwk.json`, and `x5c` is the verifier leaf only.
- `_features.conf`, `dev-mode.conf`, `transaction-data-profiles.conf` and `web.conf` are unchanged.
- Per-session settings (signed request, `direct_post`, policies, 10-minute expiry) are sent by Next.js in `POST /verification-session/create`. See `web/src/lib/verifier2.ts`.
