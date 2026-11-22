#!/usr/bin/env python3
"""
SimFone MDM offline admin tool. Runs on an offline admin computer.

  Master key (offline, back it up in 2+ places like the signing keystore):
    simfone_admin.py new-master  master.key

  Per-device release key K_dev = HMAC-SHA256(master, deviceId), installed at enrollment:
    simfone_admin.py device-key  master.key  DEVICE_ID
      -> put the hex value in the per-device QR extras as "com.simfone.RELEASE_KEY", or:
         adb shell am broadcast -a com.simfone.mdm.SET_RELEASE_KEY \
             -n com.simfone.mdm/.receiver.ReleaseKeyReceiver --es key <hex>

  Response to a challenge shown in the phone's hidden settings (*#*#7463#*#*):
    simfone_admin.py response  master.key  DEVICE_ID  CHALLENGE  ACTION
      ACTION = settings | unlock | release | park | reactivate

  Fallback manifest (ECDSA P-256), needs: pip install cryptography
    simfone_admin.py new-manifest-key  manifest_private.pem
      -> prints the public key for gradle property SIMFONE_MANIFEST_PUBKEY
    simfone_admin.py sign-manifest  manifest_private.pem  ACTION  TARGETS  DAYS [NEW_SERVER_URL] > manifest.json
      ACTION  = release | park | newServerUrl
      TARGETS = "*" or comma-separated device IDs
      DAYS    = validity in days from now
    simfone_admin.py verify-manifest  PUBKEY_B64  manifest.json
"""
import base64
import hashlib
import hmac
import json
import os
import secrets
import sys
import time

ACTIONS = {"settings", "unlock", "release", "park", "reactivate"}


def read_master(path):
    with open(path, "r") as f:
        key = bytes.fromhex(f.read().strip())
    if len(key) < 32:
        sys.exit("master key too short")
    return key


def device_key(master, device_id):
    return hmac.new(master, device_id.encode("utf-8"), hashlib.sha256).digest()


def response(k_dev, challenge, action):
    """Must match OfflineCode.compute()/formatResponse() in the app."""
    h = hmac.new(k_dev, f"{challenge}|{action}".encode("utf-8"), hashlib.sha256).digest()
    v = int.from_bytes(h[:8], "big") & 0x7FFFFFFFFFFFFFFF
    return f"{v % 10_000_000_000:010d}"


def cmd_new_master(path):
    if os.path.exists(path):
        sys.exit(f"{path} exists, refusing to overwrite")
    with open(path, "w") as f:
        f.write(secrets.token_bytes(32).hex() + "\n")
    os.chmod(path, 0o600)
    print(f"Master key written to {path}. Back it up offline in 2+ places.")


def cmd_sign_manifest(key_path, action, targets, days, new_url=None):
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import ec

    if action not in ("release", "park", "newServerUrl"):
        sys.exit("action must be release, park or newServerUrl")
    if action == "newServerUrl" and not (new_url or "").startswith("https://"):
        sys.exit("newServerUrl needs an https:// URL")
    with open(key_path, "rb") as f:
        key = serialization.load_pem_private_key(f.read(), password=None)
    now = int(time.time())
    payload = {
        "v": 1,
        "targets": "*" if targets == "*" else [t.strip() for t in targets.split(",") if t.strip()],
        "action": action,
        "notBefore": now - 300,
        "expires": now + int(float(days) * 86400),
        "nonce": secrets.token_hex(16),
    }
    if new_url:
        payload["newServerUrl"] = new_url
    raw = json.dumps(payload, separators=(",", ":")).encode("utf-8")
    sig = key.sign(raw, ec.ECDSA(hashes.SHA256()))
    print(json.dumps({"payload": base64.b64encode(raw).decode(), "sig": base64.b64encode(sig).decode()}, indent=1))


def cmd_new_manifest_key(path):
    from cryptography.hazmat.primitives import serialization
    from cryptography.hazmat.primitives.asymmetric import ec

    if os.path.exists(path):
        sys.exit(f"{path} exists, refusing to overwrite")
    key = ec.generate_private_key(ec.SECP256R1())
    with open(path, "wb") as f:
        f.write(key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8,
                                  serialization.NoEncryption()))
    os.chmod(path, 0o600)
    pub = key.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
    print("SIMFONE_MANIFEST_PUBKEY=" + base64.b64encode(pub).decode())


def cmd_verify_manifest(pub_b64, path):
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import ec

    pub = serialization.load_der_public_key(base64.b64decode(pub_b64))
    env = json.load(open(path))
    raw = base64.b64decode(env["payload"])
    pub.verify(base64.b64decode(env["sig"]), raw, ec.ECDSA(hashes.SHA256()))
    print("signature OK:", raw.decode())


def main(a):
    if len(a) < 2 or a[1] in ("-h", "--help"):
        print(__doc__)
        return
    c = a[1]
    if c == "new-master" and len(a) == 3:
        cmd_new_master(a[2])
    elif c == "device-key" and len(a) == 4:
        print(device_key(read_master(a[2]), a[3]).hex())
    elif c == "response" and len(a) == 6:
        if a[5] not in ACTIONS:
            sys.exit(f"action must be one of {sorted(ACTIONS)}")
        print(response(device_key(read_master(a[2]), a[3]), a[4].strip(), a[5]))
    elif c == "new-manifest-key" and len(a) == 3:
        cmd_new_manifest_key(a[2])
    elif c == "sign-manifest" and len(a) in (6, 7):
        cmd_sign_manifest(a[2], a[3], a[4], a[5], a[6] if len(a) == 7 else None)
    elif c == "verify-manifest" and len(a) == 4:
        cmd_verify_manifest(a[2], a[3])
    elif c == "selftest":
        print(response(device_key(b"\x01" * 32, "TEST-001"), "123456", "release"))
    else:
        print(__doc__)
        sys.exit(1)


if __name__ == "__main__":
    main(sys.argv)
