#!/usr/bin/env bash
# One-time signing setup for an existing repository with no published releases.
set -euo pipefail
for cmd in gh keytool openssl base64; do
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
if [[ ! -e "$KEY" && ! -e "$PASSFILE" ]]; then
  if grep -Eq '^ANDROID_(KEYSTORE_BASE64|KEYSTORE_PASSWORD|KEY_ALIAS|KEY_PASSWORD)$' <<< "$SECRETS"; then
    echo 'Signing secrets already exist but the local backup is missing. Restore the original key; refusing to replace it.' >&2
    exit 1
  fi
  openssl rand -hex 32 > "$PASSFILE"
  keytool -genkeypair -storetype PKCS12 -keystore "$KEY" -storepass:file "$PASSFILE" -keypass:file "$PASSFILE" -alias distribution -keyalg RSA -keysize 3072 -validity 10000 -dname 'CN=AI Command Center'
fi
[[ -s "$KEY" && -s "$PASSFILE" ]] || { echo 'Incomplete key backup; restore original files.' >&2; exit 1; }
keytool -list -storetype PKCS12 -keystore "$KEY" -storepass:file "$PASSFILE" -alias distribution >/dev/null
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
