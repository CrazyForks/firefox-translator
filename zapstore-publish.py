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
import re
import subprocess
import sys
import tempfile
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
    app_id: str


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

    blob_hashes, apk_versions, app_id = set(), {}, None
    for e in events:
        tags = {t[0]: t[1] for t in e["tags"] if len(t) > 1}
        for t in e["tags"]:
            if len(t) > 1 and t[0] in ("icon", "image", "url") and t[1].startswith(f"{CDN}/"):
                blob_hashes.add(t[1].rsplit("/", 1)[1])
        if e["kind"] == 3063:
            apk_versions[tags["x"]] = tags["version"]
            app_id = tags["i"]

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
    if app_id is None:
        sys.exit("error: zsp emitted no kind-3063 event, cannot determine the app id")
    return Plan(events, blob_hashes, apk_versions, staged_files, app_id)


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


# zsp re-hosts the icon and screenshots it pulls from f-droid.org, but only names
# them by hash in the events; it reveals the files it staged on non-quiet stderr,
# which we cannot use because non-quiet zsp opens an interactive APK-arch selector
# and dies without a TTY. Re-fetching f-droid's images and hashing them recovers
# the same mapping without depending on zsp's output format.
def fdroid_images(app_id: str, dest: Path) -> dict[str, Path]:
    url = f"https://f-droid.org/en/packages/{app_id}/"
    try:
        with urllib.request.urlopen(url) as resp:
            page = resp.read().decode()
    except urllib.error.HTTPError as e:
        sys.exit(f"error: fetching {url} failed ({e.code})")

    pattern = rf'https://f-droid\.org/repo/{re.escape(app_id)}/[^"]+?\.(?:png|jpg|jpeg)'
    images = {}
    for image_url in sorted(set(re.findall(pattern, page))):
        with urllib.request.urlopen(image_url) as resp:
            data = resp.read()
        path = dest / image_url.rsplit("/", 1)[1]
        path.write_bytes(data)
        images[hashlib.sha256(data).hexdigest()] = path
    return images


def resolve_uploads(plan: Plan, missing: list[str], dest: Path) -> list[tuple[Path, str]]:
    uploads, images = [], None
    for blob_hash in missing:
        staged = plan.staged_files.get(blob_hash)
        if staged is not None and staged.is_file():
            uploads.append((staged, blob_hash))
            continue

        version = plan.apk_versions.get(blob_hash)
        if version is not None:
            apk = Path(f"signed/translator-arm64-{version}.apk")
            if not apk.is_file():
                sys.exit(f"error: blob {blob_hash} is the v{version} APK but {apk} does not exist")
            if hashlib.sha256(apk.read_bytes()).hexdigest() != blob_hash:
                sys.exit(f"error: {apk} does not hash to {blob_hash} — "
                         "local APK differs from the GitHub release asset")
            uploads.append((apk, blob_hash))
            continue

        if images is None:
            print(f"    fetching f-droid images for {plan.app_id}")
            images = fdroid_images(plan.app_id, dest)
        path = images.get(blob_hash)
        if path is None:
            sys.exit(f"error: blob {blob_hash} is neither the APK nor one of the "
                     f"{len(images)} images f-droid serves for {plan.app_id}")
        uploads.append((path, blob_hash))
    return uploads


def nak(args: list[str], stdin: str | None, nsec: str) -> str:
    env = os.environ | {"NOSTR_SECRET_KEY": nsec}
    proc = subprocess.run(["nak", *args], input=stdin, env=env, capture_output=True, text=True)
    if proc.returncode != 0:
        sys.exit(f"nak {args[0]} failed:\n{proc.stderr}")
    return proc.stdout


# curl rather than urllib: urllib buffers the whole APK in memory and sends it
# over HTTP/1.1 without Expect: 100-continue, so a server-side rejection arrives
# as a TLS-level EOF mid-body (SSLEOFError) with the real reason lost. curl
# streams from disk and lets the server answer before the body is sent.
def upload_blob(file: Path, blob_hash: str, nsec: str) -> None:
    auth_event = nak(
        ["event", "-k", "24242", "-c", f"Upload {file.name}",
         "-t", "t=upload", "-t", f"x={blob_hash}", "-t", f"expiration={int(time.time()) + 3600}"],
        stdin=None, nsec=nsec).strip()
    mime = ("application/vnd.android.package-archive" if file.suffix == ".apk"
            else "image/png" if file.suffix == ".png" else "application/octet-stream")

    with tempfile.NamedTemporaryFile("w+", suffix=".headers") as headers_file:
        proc = subprocess.run([
            "curl", "-sS", "--fail-with-body", "--upload-file", str(file),
            "-H", "Authorization: Nostr " + base64.b64encode(auth_event.encode()).decode(),
            # bare hex; the server 400s on the RFC 9530 "sha-256=:<base64>:" form
            "-H", f"Content-Digest: {blob_hash}",
            "-H", f"Content-Type: {mime}",
            "-D", headers_file.name, f"{CDN}/upload",
        ], capture_output=True, text=True)
        headers_file.seek(0)
        headers = headers_file.read()

    if proc.returncode != 0:
        status = headers.splitlines()[0] if headers else "(no response headers)"
        reason = next((line.split(":", 1)[1].strip() for line in headers.splitlines()
                       if line.lower().startswith("x-reason:")), "")
        sys.exit(f"upload of {file} failed: {status}\n"
                 f"    reason: {reason or '(none given)'}\n"
                 f"    body: {proc.stdout.strip()}\n"
                 f"    curl: {proc.stderr.strip()}")
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
    staging = Path(tempfile.mkdtemp(prefix="zapstore-blobs-"))
    uploads = resolve_uploads(plan, missing, staging)

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
