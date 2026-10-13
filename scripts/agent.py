"""Plays the personal agent for manual testing, until OpenClaw is wired in.

It does only what an agent may do: hand the wallet a link over MCP, poll, and
pass any approval link to you. It never opens or answers the approval page.

Usage (from the repo root):
  python3 -I scripts/agent.py acquire '<openid-credential-offer://... link>'
  python3 -I scripts/agent.py present '<openid4vp://... link>'
  python3 -I scripts/agent.py --agent openclaw present '<link>'

The API key is read from wallet/data/dev-agent-<agent>.txt and never printed.
"""
import argparse
import json
import ssl
import sys
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
WALLET_MCP = "https://wallet.localtest.me/mcp"
CTX = ssl.create_default_context(cafile=str(ROOT / "infra" / "certs" / "rootCA.pem"))


def load_key(agent):
    path = ROOT / "wallet" / "data" / f"dev-agent-{agent}.txt"
    try:
        return next(t for t in path.read_text().split() if t.startswith("a2wp_"))
    except (FileNotFoundError, StopIteration):
        sys.exit(f"No API key for agent '{agent}' in {path}")


class Wallet:
    def __init__(self, key):
        self.key, self.n = key, 0
        self.rpc("initialize", {"protocolVersion": "2025-06-18", "capabilities": {},
                                "clientInfo": {"name": "manual-agent", "version": "1"}})

    def rpc(self, method, params):
        self.n += 1
        body = json.dumps({"jsonrpc": "2.0", "id": self.n, "method": method, "params": params}).encode()
        req = urllib.request.Request(WALLET_MCP, data=body, method="POST", headers={
            "authorization": f"Bearer {self.key}", "content-type": "application/json",
            "accept": "application/json, text/event-stream", "mcp-protocol-version": "2025-06-18"})
        try:
            with urllib.request.urlopen(req, context=CTX, timeout=40) as r:
                return json.loads(r.read())["result"]
        except urllib.error.HTTPError as e:
            sys.exit(f"Wallet answered HTTP {e.code}. Is the API key right and the wallet running?")
        except urllib.error.URLError as e:
            sys.exit(f"Cannot reach {WALLET_MCP}: {e.reason}")

    def tool(self, name, args):
        result = self.rpc("tools/call", {"name": name, "arguments": args})
        print(f"wallet> {result['content'][0]['text']}")
        if result.get("isError"):
            sys.exit(1)
        return result["structuredContent"]["operation"]


def main():
    p = argparse.ArgumentParser(description="Play the agent: hand a link to the wallet.")
    p.add_argument("--agent", default="manual", help="wallet agent id (default: manual)")
    p.add_argument("action", choices=["acquire", "present"])
    p.add_argument("link")
    a = p.parse_args()

    wallet = Wallet(load_key(a.agent))
    tool, field = ("acquire_credential", "offer") if a.action == "acquire" else ("present_credential", "request")
    op = wallet.tool(tool, {field: a.link})

    shown = None
    while op["status"] not in ("completed", "failed", "cancelled"):
        if op["status"] == "interaction_required":
            uri = op["interaction"]["interaction_uri"]
            if uri != shown:
                print(f"\nagent> The wallet needs you. Open this link in your browser and decide:\n\n  {uri}\n")
                shown = uri
        op = wallet.tool("get_operation", {"operation_id": op["operation_id"], "wait_seconds": 25})

    print("\n" + json.dumps({k: op[k] for k in ("status", "result", "error") if k in op}, indent=2))


if __name__ == "__main__":
    main()
