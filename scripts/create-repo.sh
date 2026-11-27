#!/usr/bin/env bash
# Run from an authenticated GitHub CLI on your own computer (Git Bash/WSL on Windows).
set -euo pipefail
cd "$(dirname "$0")/.."
for cmd in gh git keytool openssl; do command -v "$cmd" >/dev/null || { echo "Required: $cmd" >&2; exit 1; }; done
gh auth status
OWNER=$(gh api user --jq .login)
REPO="${1:-$OWNER/ai-command-center}"
[[ "$REPO" =~ ^[A-Za-z0-9-]+/[A-Za-z0-9_.-]+$ ]] || { echo 'Use owner/repository'; exit 1; }
KEY_DIR="${AI_COMMAND_KEY_DIR:-$HOME/.ai-command-center-signing}"
# Never publish or replace an existing repository/key automatically.
if gh repo view "$REPO" >/dev/null 2>&1; then echo "Repository already exists. Refusing to overwrite it."; exit 1; fi
if [[ -e .git ]]; then echo 'Use a fresh extracted source directory (no .git).'; exit 1; fi
printf 'Create PUBLIC repository %s? Public access is required for token-free updates. [y/N] ' "$REPO"
read -r answer
[[ "$answer" == y || "$answer" == Y ]] || exit 1
umask 077
mkdir -p "$KEY_DIR"
KEY_DIR=$(cd "$KEY_DIR" && pwd)
KEY="$KEY_DIR/distribution.p12"
PASSFILE="$KEY_DIR/password"
if [[ ! -e "$KEY" && ! -e "$PASSFILE" ]]; then
  openssl rand -hex 32 > "$PASSFILE"
  keytool -genkeypair -storetype PKCS12 -keystore "$KEY" -storepass:file "$PASSFILE" -keypass:file "$PASSFILE" -alias distribution -keyalg RSA -keysize 3072 -validity 10000 -dname 'CN=AI Command Center'
fi
[[ -f "$KEY" && -s "$PASSFILE" ]] || { echo 'Incomplete key backup; restore original files. Do not generate a replacement.'; exit 1; }
keytool -list -storetype PKCS12 -keystore "$KEY" -storepass:file "$PASSFILE" -alias distribution >/dev/null
# Set secrets before the first push, avoiding an unsigned or failed initial release.
git init -b main
git add .
git commit -m 'Initial AI Command Center signed release and updater'
gh repo create "$REPO" --public --source=. --remote=origin
base64 < "$KEY" | tr -d '\r\n' | gh secret set ANDROID_KEYSTORE_BASE64 -R "$REPO"
gh secret set ANDROID_KEYSTORE_PASSWORD -R "$REPO" < "$PASSFILE"
gh secret set ANDROID_KEY_PASSWORD -R "$REPO" < "$PASSFILE"
printf '%s' distribution | gh secret set ANDROID_KEY_ALIAS -R "$REPO"
git push -u origin main
printf '\nRepository: https://github.com/%s\nReleases: https://github.com/%s/releases\nKeep a secure backup of %s; loss of this key prevents compatible updates.\n' "$REPO" "$REPO" "$KEY_DIR"
