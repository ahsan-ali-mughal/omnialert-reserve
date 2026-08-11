#!/usr/bin/env python3
"""
Mints a short-lived RS256 JWT signed with dev-keys/private_key.pem so you
can call the API locally without a full OAuth2 authorization server.

Usage:
    pip install pyjwt cryptography --break-system-packages
    python3 scripts/generate-test-jwt.py

NEVER use this in production - it's a local dev/testing convenience only.
"""
import time
import pathlib

try:
    import jwt
except ImportError:
    raise SystemExit("Run: pip install pyjwt cryptography --break-system-packages")

PRIVATE_KEY_PATH = pathlib.Path(__file__).parent.parent / "dev-keys" / "private_key.pem"

def main():
    private_key = PRIVATE_KEY_PATH.read_text()

    now = int(time.time())
    payload = {
        "sub": "8832",
        "scope": "orders:write",
        "iat": now,
        "exp": now + 3600,
    }

    token = jwt.encode(payload, private_key, algorithm="RS256")
    print(token)

if __name__ == "__main__":
    main()
