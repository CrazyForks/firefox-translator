#!/usr/bin/env python3
# Lightning-address (LUD-16) + NIP-57 zap server in front of phoenixd.
#
# Exposes  https://<DOMAIN>/.well-known/lnurlp/<NAME>  ->  LNURL-pay params
#          <PUBLIC_BASE_URL>/lnurlp/callback           ->  a bolt11 invoice
#          /webhook                                     <-  phoenixd payment notices
#
# phoenixd holds the funds; this process only mints invoices (use the
# LIMITED-access password — it cannot send) and, if a Nostr key is given,
# publishes kind-9735 zap receipts via `nak` once a zap invoice is paid.
#
# Put it behind your existing TLS reverse proxy on davidv.dev. The proxy must
# route  /.well-known/lnurlp/<NAME>  and  <callback path>  to this server.
#
# === phoenixd API spots to confirm against your version (could not fetch the
#     live doc; written from the documented API):
#   1. /createinvoice accepts `descriptionHash` (hex). Required for LNURL — a
#      bolt11 must carry the metadata hash, not plain text.
#   2. per-invoice `webhookUrl` form param is honored.
#   3. GET /payments/incoming/<hash> returns a paid flag (receivedSat>0).
#
# Config via env (see DEFAULTS below).
import base64
import hashlib
import json
import os
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PHOENIXD_URL = os.environ.get("PHOENIXD_URL", "http://127.0.0.1:9740")
PHOENIXD_PASSWORD = os.environ.get("PHOENIXD_PASSWORD", "")  # http-password-limited-access
NAME = os.environ.get("LN_ADDRESS_NAME", "david")
DOMAIN = os.environ.get("LN_ADDRESS_DOMAIN", "davidv.dev")
PUBLIC_BASE_URL = os.environ.get("PUBLIC_BASE_URL", f"https://{DOMAIN}").rstrip("/")
CALLBACK_PATH = "/lnurlp/callback"
WEBHOOK_PATH = "/webhook"
MIN_SENDABLE_MSAT = int(os.environ.get("MIN_SENDABLE_MSAT", 1_000))
MAX_SENDABLE_MSAT = int(os.environ.get("MAX_SENDABLE_MSAT", 100_000_000))
LISTEN_HOST = os.environ.get("LISTEN_HOST", "127.0.0.1")
LISTEN_PORT = int(os.environ.get("LISTEN_PORT", 8080))

# Nostr zaps are enabled only when both are set; the secret key MUST correspond
# to the pubkey, since that key signs the zap receipts advertised in lnurlp.
NOSTR_PUBKEY_HEX = os.environ.get("NOSTR_PUBKEY_HEX", "")
NOSTR_SECRET_KEY = os.environ.get("NOSTR_SECRET_KEY", "")
DEFAULT_RELAYS = [r for r in os.environ.get("RELAYS", "wss://relay.zapstore.dev").split(",") if r]
ZAPS_ENABLED = bool(NOSTR_PUBKEY_HEX and NOSTR_SECRET_KEY)

LN_ADDRESS = f"{NAME}@{DOMAIN}"
LNURLP_METADATA = json.dumps([
    ["text/plain", f"Pay to {LN_ADDRESS}"],
    ["text/identifier", LN_ADDRESS],
])

# paymentHash -> {"zap_request": str, "bolt11": str, "relays": [str]}
pending_zaps: dict[str, dict] = {}


def lnurlp_params() -> dict:
    params = {
        "callback": f"{PUBLIC_BASE_URL}{CALLBACK_PATH}",
        "minSendable": MIN_SENDABLE_MSAT,
        "maxSendable": MAX_SENDABLE_MSAT,
        "metadata": LNURLP_METADATA,
        "commentAllowed": 255,
        "tag": "payRequest",
    }
    if ZAPS_ENABLED:
        params["allowsNostr"] = True
        params["nostrPubkey"] = NOSTR_PUBKEY_HEX
    return params


def phoenixd(path: str, form: dict | None = None) -> dict:
    auth = base64.b64encode(f":{PHOENIXD_PASSWORD}".encode()).decode()
    headers = {"Authorization": f"Basic {auth}"}
    data = urllib.parse.urlencode(form).encode() if form is not None else None
    req = urllib.request.Request(f"{PHOENIXD_URL}{path}", data=data, headers=headers)
    with urllib.request.urlopen(req, timeout=15) as resp:
        return json.loads(resp.read())


def create_invoice(amount_msat: int, description_hash_hex: str) -> dict:
    form = {
        "amountSat": amount_msat // 1000,
        "descriptionHash": description_hash_hex,
    }
    if ZAPS_ENABLED:
        form["webhookUrl"] = f"{PUBLIC_BASE_URL}{WEBHOOK_PATH}"
    return phoenixd("/createinvoice", form)


def callback_invoice(amount_msat: int, nostr_param: str | None) -> dict:
    if not MIN_SENDABLE_MSAT <= amount_msat <= MAX_SENDABLE_MSAT:
        return {"status": "ERROR", "reason": "amount out of range"}

    if not (ZAPS_ENABLED and nostr_param):
        # plain LUD-06: invoice description_hash binds to the metadata string
        dh = hashlib.sha256(LNURLP_METADATA.encode()).hexdigest()
        inv = create_invoice(amount_msat, dh)
        return {"pr": inv["serialized"], "routes": []}

    # NIP-57 zap: description_hash binds to the zap request itself
    zap_request = json.loads(nostr_param)
    tags = {t[0]: t[1:] for t in zap_request.get("tags", []) if t}
    if "amount" in tags and int(tags["amount"][0]) != amount_msat:
        return {"status": "ERROR", "reason": "amount does not match zap request"}

    dh = hashlib.sha256(nostr_param.encode()).hexdigest()
    inv = create_invoice(amount_msat, dh)
    relays = tags["relays"] if "relays" in tags else []
    pending_zaps[inv["paymentHash"]] = {
        "zap_request": nostr_param,
        "bolt11": inv["serialized"],
        "relays": list(dict.fromkeys(DEFAULT_RELAYS + relays)),
    }
    return {"pr": inv["serialized"], "routes": []}


def is_paid(payment_hash: str) -> bool:
    try:
        info = phoenixd(f"/payments/incoming/{payment_hash}")
    except urllib.error.HTTPError:
        return False
    return bool(info.get("receivedSat") or info.get("isPaid"))


def publish_zap_receipt(payment_hash: str) -> None:
    zap = pending_zaps.get(payment_hash)
    if zap is None:
        return
    if not is_paid(payment_hash):  # guard against spoofed webhook calls
        return

    zap_request = json.loads(zap["zap_request"])
    src = {t[0]: t for t in zap_request.get("tags", []) if t}
    tags = [["bolt11", zap["bolt11"]], ["description", zap["zap_request"]]]
    for key in ("p", "e", "a"):
        if key in src:
            tags.append(src[key])
    tags.append(["P", zap_request["pubkey"]])

    receipt = {"kind": 9735, "content": "", "tags": tags}
    env = os.environ | {"NOSTR_SECRET_KEY": NOSTR_SECRET_KEY}
    subprocess.run(
        ["nak", "event", *zap["relays"]],
        input=json.dumps(receipt), env=env, text=True,
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
    )
    del pending_zaps[payment_hash]


class Handler(BaseHTTPRequestHandler):
    def _send_json(self, obj: dict, status: int = 200) -> None:
        body = json.dumps(obj).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self) -> None:
        url = urllib.parse.urlparse(self.path)
        query = urllib.parse.parse_qs(url.query)

        if url.path == f"/.well-known/lnurlp/{NAME}":
            self._send_json(lnurlp_params())
            return

        if url.path == CALLBACK_PATH:
            if "amount" not in query:
                self._send_json({"status": "ERROR", "reason": "missing amount"}, 400)
                return
            nostr = query.get("nostr", [None])[0]
            self._send_json(callback_invoice(int(query["amount"][0]), nostr))
            return

        self._send_json({"status": "ERROR", "reason": "not found"}, 404)

    def do_POST(self) -> None:
        if urllib.parse.urlparse(self.path).path != WEBHOOK_PATH:
            self._send_json({"status": "ERROR", "reason": "not found"}, 404)
            return
        length = int(self.headers.get("Content-Length", 0))
        payload = json.loads(self.rfile.read(length) or b"{}")
        payment_hash = payload.get("paymentHash") or payload.get("payment_hash")
        if payment_hash:
            publish_zap_receipt(payment_hash)
        self._send_json({"status": "OK"})

    def log_message(self, fmt, *args):
        sys.stderr.write(f"{self.address_string()} {fmt % args}\n")


def main() -> None:
    if not PHOENIXD_PASSWORD:
        sys.exit("set PHOENIXD_PASSWORD (the http-password-limited-access from phoenix.conf)")
    print(f"lnaddress: {LN_ADDRESS}  zaps={'on' if ZAPS_ENABLED else 'off'}")
    print(f"listening on {LISTEN_HOST}:{LISTEN_PORT}, phoenixd at {PHOENIXD_URL}")
    ThreadingHTTPServer((LISTEN_HOST, LISTEN_PORT), Handler).serve_forever()


if __name__ == "__main__":
    main()
