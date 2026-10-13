#!/usr/bin/env bash
# Runs inside the OpenClaw VM. Connects OpenClaw to the agent's A2WP wallet as a remote MCP server.
# The agent's API key arrives on stdin and is written only to ~/.openclaw/.env (mode 600);
# the OpenClaw config refers to it as ${A2WP_WALLET_KEY}, so the key never appears in config or output.
#
# From the host:
#   limactl copy scripts/vm-connect-wallet.sh openclaw:vm-connect-wallet.sh
#   limactl shell openclaw -- bash -l vm-connect-wallet.sh < wallet/data/dev-agent-openclaw.txt
set -euo pipefail
umask 077

# stdin is the wallet's dev-agent-<agent>.txt: "agent_id: …", "api_key: …", "api_key_sha256: …".
key=$(sed -n 's/^api_key:[[:space:]]*//p')
key=$(printf %s "$key" | tr -d '[:space:]')
[ -n "$key" ] || { echo "no api_key line on stdin" >&2; exit 1; }

mkdir -p "$HOME/.openclaw"
envfile="$HOME/.openclaw/.env"
touch "$envfile"
chmod 600 "$envfile"
{ grep -v '^A2WP_WALLET_KEY=' "$envfile" || true; printf 'A2WP_WALLET_KEY=%s\n' "$key"; } > "$envfile.new"
mv -f "$envfile.new" "$envfile"
unset key

openclaw mcp set wallet '{"url":"https://wallet.localtest.me/mcp","transport":"streamable-http","headers":{"Authorization":"Bearer ${A2WP_WALLET_KEY}"},"connectionTimeoutMs":10000,"requestTimeoutMs":120000}'
stat -c '%A %n' "$envfile"
openclaw mcp probe wallet
