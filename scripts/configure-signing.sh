#!/usr/bin/env bash
# One-time signing setup for an existing repository with no published releases.
set -euo pipefail
for cmd in gh openssl base64; do
  command -v "$cmd" >/dev/null || { echo "Required: $cmd" >&2; exit 1; }
done
gh auth status
REPO="${1:-topgun1111/Ai-Command-Center}"
[[ "$REPO" =~ ^[A-Za-z0-9-]+/[A-Za-z0-9_.-]+$ ]] || { echo 'Use owner/repository' >&2; exit 1; }
gh repo view "$REPO" --json nameWithOwner --jq .nameWithOwner
RELEASES=$(gh api --paginate "repos/$REPO/releases?per_page=100" --jq '.[].tag_name')
[[ -z "$RELEASES" ]] || { echo 'A release already exists. Restore its original signing key manually; do not bootstrap a new key.' >&2; exit 1; }
SECRETS=$(gh secret list -R "$REPO" --json name --jq '.[].name')
KEY_DIR="${AI_COMMAND_KEY_DIR:-$HOME/.ai-command-center-signing}"
KEY="$KEY_DIR/distribution.p12"
PASSFILE="$KEY_DIR/password"
umask 077
mkdir -p "$KEY_DIR"
chmod 700 "$KEY_DIR"
if [[ ! -e "$KEY" ]]; then
  if grep -Eq '^ANDROID_(KEYSTORE_BASE64|KEYSTORE_PASSWORD|KEY_ALIAS|KEY_PASSWORD)$' <<< "$SECRETS"; then
    echo 'Signing secrets already exist but the local backup is missing. Restore the original key; refusing to replace it.' >&2
    exit 1
  fi
  # A failed earlier keytool attempt may have left only the password.
  # Reuse it only when no signing secrets or releases exist.
  if [[ ! -e "$PASSFILE" ]]; then openssl rand -hex 32 > "$PASSFILE"; fi
  [[ -s "$PASSFILE" ]] || { echo 'Empty password file; restore or securely move the incomplete backup.' >&2; exit 1; }
  TMP=$(mktemp -d "$KEY_DIR/.generate.XXXXXXXX")
  trap 'rm -rf "$TMP"' EXIT
  openssl req -new -x509 -newkey rsa:3072 -sha256 -days 10000 \
    -subj '/CN=AI Command Center' -keyout "$TMP/key.pem" -out "$TMP/cert.pem" \
    -passout "file:$PASSFILE"
  openssl pkcs12 -export -inkey "$TMP/key.pem" -in "$TMP/cert.pem" \
    -name distribution -out "$TMP/distribution.p12" \
    -passin fd:3 -passout fd:4 3<"$PASSFILE" 4<"$PASSFILE"
  openssl pkcs12 -in "$TMP/distribution.p12" -passin "file:$PASSFILE" -noout
  # Do not overwrite a key if another setup process created one concurrently.
  ln "$TMP/distribution.p12" "$KEY"
  rm -rf "$TMP"
  trap - EXIT
fi
[[ -s "$KEY" && -s "$PASSFILE" ]] || { echo 'Incomplete key backup; restore original files.' >&2; exit 1; }
openssl pkcs12 -in "$KEY" -passin "file:$PASSFILE" -noout
printf 'Configure permanent signing secrets in %s using %s? [y/N] ' "$REPO" "$KEY_DIR"
read -r answer
[[ "$answer" == y || "$answer" == Y ]] || exit 1
base64 < "$KEY" | tr -d '\r\n' | gh secret set ANDROID_KEYSTORE_BASE64 -R "$REPO"
gh secret set ANDROID_KEYSTORE_PASSWORD -R "$REPO" < "$PASSFILE"
gh secret set ANDROID_KEY_PASSWORD -R "$REPO" < "$PASSFILE"
printf '%s' distribution | gh secret set ANDROID_KEY_ALIAS -R "$REPO"
echo "Keep an encrypted backup of $KEY_DIR; never commit or share these files."
gh workflow run android.yml -R "$REPO" --ref main
echo "Build requested: https://github.com/$REPO/actions"
