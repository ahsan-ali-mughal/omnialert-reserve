#!/usr/bin/env bash
# Generates the RSA key pair used to verify OAuth2 JWTs.
# The private key is used ONLY by your token issuer (e.g. an auth server or
# a local script for testing) and should never be committed to source control.
# The public key ships with the app so it can verify signatures locally.
set -euo pipefail
cd "$(dirname "$0")"

openssl genpkey -algorithm RSA -out private_key.pem -pkeyopt rsa_keygen_bits:2048
openssl rsa -pubout -in private_key.pem -out public_key.pem

echo "Generated private_key.pem (keep secret, do NOT deploy with the app)"
echo "Generated public_key.pem (bundled with the app via JWT_PUBLIC_KEY_PATH)"
