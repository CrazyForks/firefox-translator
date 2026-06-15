#!/usr/bin/env python3
# Publish the latest GitHub release to Zapstore.
#
# zsp never sees a key: SIGN_WITH=<npub> makes it emit unsigned events.
# nak signs everything locally (publish events + Blossom upload auth);
# the nsec comes from $NOSTR_SECRET_KEY if set, otherwise it is prompted
# once and lives only in this process's memory.
#
# Usage: ./zapstore-publish.py [extra zsp flags, e.g. --overwrite-release]
import base64
import getpass
import hashlib
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request
from dataclasses import dataclass
from pathlib import Path

ZSP = os.environ.get("ZSP", os.path.expanduser("~/Downloads/zsp-0.4.10-linux-amd64"))
NPUB = "npub136ar9469eapzhprs6382v3xzkysn7mnt7y0ns0zs9jq44ku6tzxq2w5m3f"
PUBKEY_HEX = "8eba32d745cf422b8470d44ea644c2b1213f6e6bf11f383c502c815adb9a588c"
RELAY = "wss://relay.zapstore.dev"
CDN = "https://cdn.zapstore.dev"


@dataclass
class Plan:
    events: list[dict]
    blob_hashes: set[str]
    apk_versions: dict[str, str]
    staged_files: dict[str, Path]


def generate_events(extra_flags: list[str]) -> Plan:
    env = os.environ | {"SIGN_WITH": NPUB}
    cmd = [ZSP, "publish", "--quiet", "--skip-certificate-linking", *extra_flags, "zapstore.yaml"]
    proc = subprocess.run(cmd, env=env, capture_output=True, text=True)
    if proc.returncode != 0:
        sys.exit(f"zsp failed:\n{proc.stderr}")

    # zsp's stdout format varies (pretty "Kind NNNN:" headers vs plain JSONL),
    # so scan for JSON objects instead of assuming one
    decoder = json.JSONDecoder()
    events, i = [], 0
    while (j := proc.stdout.find("{", i)) >= 0:
        try:
            obj, i = decoder.raw_decode(proc.stdout, j)
        except json.JSONDecodeError:
            i = j + 1
            continue
        if isinstance(obj, dict) and {"kind", "id", "tags"} <= obj.keys():
            events.append(obj)

    blob_hashes, apk_versions = set(), {}
    for e in events:
        tags = {t[0]: t[1] for t in e["tags"] if len(t) > 1}
        for t in e["tags"]:
            if len(t) > 1 and t[0] in ("icon", "image", "url") and t[1].startswith(f"{CDN}/"):
                blob_hashes.add(t[1].rsplit("/", 1)[1])
        if e["kind"] == 3063:
            apk_versions[tags["x"]] = tags["version"]

    # files zsp staged locally (e.g. icon extracted from the APK), keyed by sha256
    staged_files, path = {}, None
    for line in proc.stderr.splitlines():
        line = line.strip()
        if line.startswith("Path:"):
            path = Path(line.split(None, 1)[1])
        if line.startswith("SHA256:") and path:
            staged_files[line.split(None, 1)[1]] = path

    if not events:
        print("Nothing to publish (release already published, or zsp produced no events):")
        print(proc.stderr)
        sys.exit(0)
    return Plan(events, blob_hashes, apk_versions, staged_files)


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *_, **__):
        return None


def cdn_status(blob_hash: str) -> int:
    opener = urllib.request.build_opener(_NoRedirect)
    req = urllib.request.Request(f"{CDN}/{blob_hash}", method="HEAD")
    try:
        with opener.open(req) as resp:
            return resp.status
    except urllib.error.HTTPError as e:
        return e.code


def resolve_blob_file(plan: Plan, blob_hash: str) -> Path:
    staged = plan.staged_files.get(blob_hash)
    if staged is not None and staged.is_file():
        return staged

    version = plan.apk_versions.get(blob_hash)
    if version is None:
        sys.exit(f"error: no local file found for blob {blob_hash} "
                 "(icon/screenshot changed? re-run zsp non-quiet to see its manifest)")
    apk = Path(f"signed/translator-arm64-{version}.apk")
    if not apk.is_file():
        sys.exit(f"error: blob {blob_hash} is the v{version} APK but {apk} does not exist")
    if hashlib.sha256(apk.read_bytes()).hexdigest() != blob_hash:
        sys.exit(f"error: {apk} does not hash to {blob_hash} — "
                 "local APK differs from the GitHub release asset")
    return apk


def nak(args: list[str], stdin: str | None, nsec: str) -> str:
    env = os.environ | {"NOSTR_SECRET_KEY": nsec}
    proc = subprocess.run(["nak", *args], input=stdin, env=env, capture_output=True, text=True)
    if proc.returncode != 0:
        sys.exit(f"nak {args[0]} failed:\n{proc.stderr}")
    return proc.stdout


def upload_blob(file: Path, blob_hash: str, nsec: str) -> None:
    auth_event = nak(
        ["event", "-k", "24242", "-c", f"Upload {file.name}",
         "-t", "t=upload", "-t", f"x={blob_hash}", "-t", f"expiration={int(time.time()) + 3600}"],
        stdin=None, nsec=nsec).strip()
    data = file.read_bytes()
    mime = ("application/vnd.android.package-archive" if file.suffix == ".apk"
            else "image/png" if file.suffix == ".png" else "application/octet-stream")
    req = urllib.request.Request(f"{CDN}/upload", data=data, method="PUT", headers={
        "Authorization": "Nostr " + base64.b64encode(auth_event.encode()).decode(),
        "Content-Digest": f"sha-256=:{base64.b64encode(hashlib.sha256(data).digest()).decode()}:",
        "Content-Type": mime,
    })
    try:
        with urllib.request.urlopen(req) as resp:
            resp.read()
    except urllib.error.HTTPError as e:
        sys.exit(f"upload of {file} failed ({e.code}): {e.read().decode(errors='replace')}")
    print(f"    {CDN}/{blob_hash}")


def verify(plan: Plan, nsec: str) -> None:
    still_missing = [h for h in plan.blob_hashes if cdn_status(h) == 404]
    if still_missing:
        sys.exit(f"error: blobs still missing after upload: {still_missing}")
    out = nak(["req", "-k", "30063", "-a", PUBKEY_HEX, "--limit", "1", RELAY], stdin=None, nsec=nsec)
    e = json.loads(out.splitlines()[0])
    tags = {t[0]: t[1] for t in e["tags"] if len(t) > 1}
    print(f"    latest release on relay: {tags.get('d')} (signed: {bool(e.get('sig'))})")


def main() -> None:
    os.chdir(Path(__file__).parent)

    print("==> Generating unsigned events with zsp")
    plan = generate_events(sys.argv[1:])
    print(f"    {len(plan.events)} events, referencing {len(plan.blob_hashes)} CDN blobs")

    print("==> Checking which referenced blobs are missing from the CDN")
    missing = []
    for blob_hash in sorted(plan.blob_hashes):
        status = cdn_status(blob_hash)
        if status == 404:
            missing.append(blob_hash)
            print(f"    {blob_hash}: MISSING")
        else:
            print(f"    {blob_hash}: on CDN ({status})")

    # fail before prompting for the key if any missing blob is unresolvable
    uploads = [(resolve_blob_file(plan, h), h) for h in missing]

    nsec = os.environ.get("NOSTR_SECRET_KEY") \
        or getpass.getpass("nsec (or pre-set NOSTR_SECRET_KEY; read once, passed only to nak in-memory): ")

    for file, blob_hash in uploads:
        print(f"==> Uploading {file}")
        upload_blob(file, blob_hash, nsec)

    print(f"==> Signing and publishing events to {RELAY}")
    for event in plan.events:
        out = nak(["event", RELAY], stdin=json.dumps(event, separators=(",", ":")), nsec=nsec)
        print("    " + out.strip().splitlines()[-1][:100])

    print("==> Verifying")
    verify(plan, nsec)
    print("Done: https://zapstore.dev/apps/dev.davidv.translator")


if __name__ == "__main__":
    main()
