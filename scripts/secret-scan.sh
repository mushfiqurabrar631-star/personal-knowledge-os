#!/usr/bin/env bash
# Final Testing SEC-05 helper: looks for signing keys, passwords and API tokens in the files tracked by Git (HEAD).
# Read-only. Exit 1 when something suspicious is found. It never prints a matched secret value, only file:line.
set -uo pipefail
cd "$(dirname "$0")/.."
found=0
# 1. Key or credential files that must never be tracked.
while IFS= read -r f; do
  echo "TRACKED KEY/CONFIG FILE: $f"; found=1
done < <(git ls-files | grep -Ei '(\.jks|\.keystore|\.p12|\.pfx|(^|/)local\.properties|(^|/)signing\.properties|(^|/)secrets\.properties|google-services\.json|client_secret.*\.json)$')
# 2. Secret-looking content (long real-format tokens; short test placeholders are ignored on purpose).
patterns=(
  'ntn_[A-Za-z0-9]{40,}'                       # Notion internal integration token
  'secret_[A-Za-z0-9]{40,}'                    # Notion legacy token
  'AIza[0-9A-Za-z_-]{35}'                      # Google API key
  'sk-(proj-)?[A-Za-z0-9_-]{32,}'              # OpenAI-style key
  '-----BEGIN ([A-Z ]+ )?PRIVATE KEY-----'     # private keys
  '(store|key)Password *= *"[^"$]{4,}"'        # hard-coded signing password literal in Gradle
  'pkos\.(release|debug)[A-Za-z]*Password *= *[^<[:space:]]' # real (not <placeholder>) password in a properties file
  'ya29\.[0-9A-Za-z_-]{20,}'                   # Google OAuth access token
)
for p in "${patterns[@]}"; do
  while IFS= read -r hit; do
    echo "SECRET-LIKE CONTENT: ${hit%%:*}:$(echo "$hit" | cut -d: -f2) (pattern ${p:0:18}…)"; found=1
  done < <(git grep -nIE -e "$p" -- . ':!scripts/secret-scan.sh' 2>/dev/null | cut -d: -f1,2)
done
if [ $found -eq 0 ]; then echo "secret-scan: no tracked key files and no secret-like content in HEAD ($(git rev-parse --short HEAD))"; fi
echo "note: Git history still contains the retired debug keystore (docs/security-debug-keystore-history.md); it is not used."
exit $found
