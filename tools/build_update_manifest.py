"""Build a signed Onvyra update envelope (onvyra-updates/latest.json).

Usage:
    python build_update_manifest.py --key <update-signing-rsa3072.pem> \
        --version-code 263 --version-name 2.7.2-reader.72 \
        --title "Onvyra 2.7.2-reader.72" --summary "..." \
        --asset a arm64-v8a:Onvyra-2.7.2-reader.72-arm64-v8a.apk \
        --asset x x86_64:...apk --asset u universal:...apk \
        --out latest.json

Run with -I to keep the working directory out of imports.
"""

import argparse
import base64
import hashlib
import json
import pathlib
import subprocess
import sys
from datetime import datetime, timezone

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding

REPO = "gegewu26-source/Onvyra-Releases"
DOWNLOAD_BASE = f"https://github.com/{REPO}/releases/download/"
ALLOWED_ABIS = {"arm64-v8a", "armeabi-v7a", "x86", "x86_64", "universal"}
ALLOWED_TYPES = {"feature", "fix", "improvement", "notice"}


def sha256_file(path: pathlib.Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--key", required=True, type=pathlib.Path)
    parser.add_argument("--tag", required=True, help="Release tag, e.g. v2.7.2-reader.72")
    parser.add_argument("--version-code", required=True, type=int)
    parser.add_argument("--version-name", required=True)
    parser.add_argument("--title", required=True)
    parser.add_argument("--summary", default="")
    parser.add_argument("--published-at", default=None)
    parser.add_argument("--changes", type=pathlib.Path, help="JSON file: [{\"type\":..,\"items\":[..]}, ..]")
    parser.add_argument("--asset", action="append", default=[], metavar="ABI:PATH")
    parser.add_argument("--out", required=True, type=pathlib.Path)
    args = parser.parse_args()

    if not 1 <= len(args.asset) <= 8:
        parser.error("provide 1-8 --asset entries")

    downloads = []
    seen = set()
    for entry in args.asset:
        if ":" not in entry:
            parser.error(f"bad --asset {entry!r}, expected ABI:PATH")
        abi, raw_path = entry.split(":", 1)
        if abi not in ALLOWED_ABIS:
            parser.error(f"unsupported ABI {abi!r}")
        if abi in seen:
            parser.error(f"duplicate ABI {abi!r}")
        seen.add(abi)
        path = pathlib.Path(raw_path).resolve()
        if not path.is_file():
            parser.error(f"asset not found: {path}")
        size = path.stat().st_size
        if not 1 <= size <= 1_500_000_000:
            parser.error(f"implausible size for {path.name}: {size}")
        url = f"{DOWNLOAD_BASE}{args.tag}/{path.name}"
        downloads.append({
            "abi": abi,
            "url": url,
            "sha256": sha256_file(path),
            "sizeBytes": size,
        })

    changes = []
    if args.changes:
        changes = json.loads(args.changes.read_text(encoding="utf-8"))
        if not isinstance(changes, list) or not 1 <= len(changes) <= 8:
            parser.error("--changes must be a JSON array of 1-8 groups")
        for group in changes:
            if group.get("type") not in ALLOWED_TYPES:
                parser.error(f"bad change type in {group!r}")
            items = group.get("items") or []
            if not 1 <= len(items) <= 24:
                parser.error("each change group needs 1-24 items")
    else:
        changes = [{"type": "improvement", "items": ["Release build."]}]

    published = args.published_at or datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    payload = {
        "schemaVersion": 1,
        "applicationId": "com.constx.onvyra",
        "versionCode": args.version_code,
        "versionName": args.version_name,
        "publishedAt": published,
        "title": args.title,
        "summary": args.summary,
        "changes": changes,
        "downloads": downloads,
    }

    payload_bytes = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    if len(payload_bytes) > 128 * 1024:
        parser.error("payload exceeds 128 KiB")

    private_key = serialization.load_pem_private_key(args.key.read_bytes(), password=None)
    signature = private_key.sign(payload_bytes, padding.PKCS1v15(), hashes.SHA256())
    envelope = {
        "payload_base64": base64.b64encode(payload_bytes).decode("ascii"),
        "signature_base64": base64.b64encode(signature).decode("ascii"),
    }
    envelope_bytes = json.dumps(envelope, separators=(",", ":")).encode("utf-8")
    if len(envelope_bytes) > 256 * 1024:
        parser.error("envelope exceeds 256 KiB")

    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_bytes(envelope_bytes)

    print(json.dumps({
        "output": str(args.out),
        "versionName": args.version_name,
        "versionCode": args.version_code,
        "downloads": [{"abi": d["abi"], "sha256": d["sha256"], "sizeBytes": d["sizeBytes"]} for d in downloads],
        "signature_verified": verify(args.key, payload_bytes, signature),
    }, ensure_ascii=False, indent=2))
    return 0


def verify(key_path: pathlib.Path, payload_bytes: bytes, signature: bytes) -> bool:
    from cryptography.hazmat.primitives.asymmetric import rsa

    private_key = serialization.load_pem_private_key(key_path.read_bytes(), password=None)
    public_key = private_key.public_key()
    assert isinstance(public_key, rsa.RSAPublicKey)
    try:
        public_key.verify(signature, payload_bytes, padding.PKCS1v15(), hashes.SHA256())
        return True
    except Exception:
        return False


if __name__ == "__main__":
    sys.exit(main())