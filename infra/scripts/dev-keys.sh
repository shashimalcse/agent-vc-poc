#!/usr/bin/env bash
# Generates a wallet API key for a demo agent. Prints the key once (give it to
# the agent's MCP config) and its SHA-256 (put it in wallet/config/wallet.json).
# Keys are never written to the repository.
set -euo pipefail

agent_id="${1:-demo-agent}"
key="a2wp_$(openssl rand -base64 32 | tr '+/' '-_' | tr -d '=')"
hash="$(printf '%s' "$key" | shasum -a 256 | cut -d' ' -f1)"

echo "agent_id:       $agent_id"
echo "api_key:        $key"
echo "api_key_sha256: $hash"
