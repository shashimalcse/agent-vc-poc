#!/usr/bin/env python3
"""Manual end-to-end check against the running infra (issuer2, verifier2, Caddy) and a running wallet.

  1. creates an offer at issuer2 (internal API on localhost:7005, as the portal does)
  2. submit the offer over MCP (through https://wallet.localtest.me/mcp)
  3. creates a verifier2 session with the WIRING.md DCQL query (localhost:7004, as checkout does)
  4. submit the request over MCP; approves on the approval page, acting as the user; checks that the
     verifier's redirect comes back as the A2WP -01 continuation with verifier2's response_code
  5. checks that verifier2 reports success

Usage: python3 -I scripts/integration.py <api-key> [--tx-code] [--manual]   (run from wallet/)
       --tx-code: the offer requires a transaction code, entered on the approval page
       --manual:  don't approve; print each approval link for a person to open
Stdlib only. The script plays the user on the approval page; a real agent must never do that.
"""
import json
import ssl
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CA = ROOT / "infra" / "certs" / "rootCA.pem"
WALLET = __import__("os").environ.get("A2WP_WALLET_URL", "https://wallet.localtest.me/mcp")
ISSUER2 = "http://localhost:7005"
VERIFIER2 = "http://localhost:7004"
VCT = "https://registry.localtest.me/credentials/agent/v1"
ISSUER = "https://issuer.localtest.me/openid4vci"

ctx = ssl.create_default_context(cafile=str(CA))


def http(method, url, body=None, headers=None, form=False):
    data = None
    headers = dict(headers or {})
    if body is not None:
        if form:
            data = urllib.parse.urlencode(body).encode()
            headers["content-type"] = "application/x-www-form-urlencoded"
        else:
            data = json.dumps(body).encode()
            headers["content-type"] = "application/json"
    req = urllib.request.Request(url, data=data, method=method, headers=headers)
    try:
        with urllib.request.urlopen(req, context=ctx if url.startswith("https") else None, timeout=40) as r:
            return r.status, r.read().decode()
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()


class Mcp:
    def __init__(self, key):
        self.key = key
        self.n = 0
        self.call_raw("initialize", {"protocolVersion": "2025-06-18", "capabilities": {},
                                     "clientInfo": {"name": "integration.py", "version": "1"}})

    def call_raw(self, method, params=None):
        self.n += 1
        st, body = http("POST", WALLET, {"jsonrpc": "2.0", "id": self.n, "method": method, "params": params or {}},
                        {"authorization": f"Bearer {self.key}", "accept": "application/json, text/event-stream",
                         "mcp-protocol-version": "2025-06-18"})
        assert st == 200, (st, body)
        return json.loads(body)["result"]

    def tool(self, name, args):
        r = self.call_raw("tools/call", {"name": name, "arguments": args})
        print(f"  {name}: {r['content'][0]['text']}")
        return r


MANUAL = False


def wait_terminal(mcp, op, approve=None):
    while True:
        s = op["status"]
        if s in ("completed", "failed", "cancelled"):
            return op
        needs_user = "interaction" in op
        if needs_user and approve is not None and MANUAL:
            print("  open in a browser:", op["interaction"]["uri"], flush=True)
            approve = None
        if needs_user and approve is not None:
            uri = op["interaction"]["uri"]
            if not WALLET.startswith("https://wallet.localtest.me/"):  # a second local instance for testing
                uri = WALLET.rsplit("/mcp", 1)[0] + "/approve/" + uri.rsplit("/approve/", 1)[1]
            st, page = http("GET", uri)
            assert st == 200, (st, page[:200])
            st, page = http("POST", uri, approve, form=True)
            print(f"  [as the user] POST {uri} -> {st}")
            approve = None
        op = mcp.tool("get", {"id": op["id"], "wait_seconds": 25})["structuredContent"]["operation"]


def main():
    key = sys.argv[1]
    tx = "--tx-code" in sys.argv[2:]
    global MANUAL
    MANUAL = "--manual" in sys.argv[2:]  # print approval links and wait for a person to use them
    mcp = Mcp(key)
    # A2WP -01: the wallet authenticates the principal on the approval page.
    passcode = __import__("os").environ.get("A2WP_APPROVAL_PASSCODE")
    auth = {"passcode": passcode} if passcode else {}

    print("1. create offer at issuer2")
    st, body = http("POST", f"{ISSUER2}/issuer2/credential-offers", {
        "profileId": "AgentCredential", "authMethod": "PRE_AUTHORIZED", "valueMode": "BY_REFERENCE",
        "expiresInSeconds": 600,
        **({"txCode": {"input_mode": "numeric", "length": 6, "description": "PIN shown on the portal"},
            "txCodeValue": "493817"} if tx else {}),
        "runtimeOverrides": {"credentialData": {
            "agent_id": "agt_it" + str(int(time.time())), "agent_name": "Integration agent",
            "provider": "OpenClaw", "operator": "Integration User", "accreditation_level": "verified"}},
    })
    assert st in (200, 201), (st, body)
    offer = json.loads(body)["credentialOffer"]
    print("  offer:", offer[:60] + "...")

    print("2. submit the offer")
    op = mcp.tool("submit", {"request": offer})["structuredContent"]["operation"]
    op = wait_terminal(mcp, op, approve={"action": "approve", **auth, **({"tx_code": "493817"} if tx else {})})
    assert op["status"] == "completed", op
    assert "continuation" not in op, op  # an issuance has no continuation
    print("  final:", json.dumps(op))

    print("3. create verifier2 session")
    st, body = http("POST", f"{VERIFIER2}/verification-session/create", {
        "flow_type": "cross_device",
        "redirects": {"success_redirect_uri": "https://tickets.localtest.me/checkout/chk_integration/signed-in"},
        "core_flow": {
            "dcql_query": {"credentials": [{"id": "agent", "format": "dc+sd-jwt", "meta": {"vct_values": [VCT]},
                                            "claims": [{"path": ["agent_id"]}, {"path": ["agent_name"]},
                                                       {"path": ["provider"]}, {"path": ["operator"]},
                                                       {"path": ["accreditation_level"]}]}],
                           "credential_sets": [{"options": [["agent"]], "required": True}]},
            "signed_request": True, "encrypted_response": False, "expiration_duration": "PT300S",
            "policies": {"vc_policies": ["signature", "expiration", "not-before",
                                        {"policy": "allowed-issuer", "allowed_issuer": ISSUER}],
                         "vp_policies": {"dc+sd-jwt": ["dc+sd-jwt/audience-check", "dc+sd-jwt/kb-jwt_signature",
                                                       "dc+sd-jwt/nonce-check", "dc+sd-jwt/sd_hash-check",
                                                       "dc+sd-jwt/kb-jwt_iat-check", "dc+sd-jwt/exp-check",
                                                       "dc+sd-jwt/nbf-check"]}},
        },
    })
    assert st in (200, 201), (st, body)
    sess = json.loads(body)
    req_url = sess.get("bootstrapAuthorizationRequestUrl") or sess["fullAuthorizationRequestUrl"]
    print("  request:", req_url[:80] + "...")

    print("4. submit the request")
    op = mcp.tool("submit", {"request": req_url})["structuredContent"]["operation"]
    op = wait_terminal(mcp, op, approve={"action": "approve", **auth})
    print("  final:", json.dumps(op))

    print("5. verifier2 session info")
    st, body = http("GET", f"{VERIFIER2}/verification-session/{sess['sessionId']}/info")
    info = json.loads(body)
    print("  status:", info.get("status"), "| policy results ok:",
          (info.get("policyResults") or info.get("policy_results") or {}).get("overallSuccess"))
    seen = [c for c in ("agent_id", "agent_name", "provider", "operator", "accreditation_level") if f'"{c}"' in body]
    print("  claims visible in verifier2 session info:", ", ".join(seen))
    continuation = op.get("continuation", "")
    print("  continuation:", continuation)
    ok = (op["status"] == "completed" and str(info.get("status", "")).upper() in ("SUCCESSFUL", "SUCCESS")
          and continuation.startswith("https://tickets.localtest.me/checkout/chk_integration/signed-in?")
          and bool(info.get("response_code")) and f"response_code={info['response_code']}" in continuation)
    if __import__("os").environ.get("A2WP_DUMP_INFO"):
        print(json.dumps(info, indent=1))
    print("RESULT:", "PASS" if ok else "CHECK OUTPUT")
    if not ok:
        print(json.dumps(info, indent=1)[:3000])
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
