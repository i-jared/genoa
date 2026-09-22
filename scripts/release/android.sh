#!/bin/zsh
set -euo pipefail
repo_root="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$repo_root/android"
if [[ "${1:-}" == "--unsigned" ]]; then
  unset GENOA_KEYSTORE GENOA_KEYSTORE_PASSWORD GENOA_KEY_ALIAS GENOA_KEY_PASSWORD
else
  : "${GENOA_KEYSTORE:?Set the absolute path to the existing release/upload keystore}"
  : "${GENOA_KEYSTORE_PASSWORD:?Set the keystore password without putting it in command history}"
  : "${GENOA_KEY_ALIAS:?Set the release/upload key alias}"
  : "${GENOA_KEY_PASSWORD:?Set the key password}"
fi
./gradlew :app:assembleRelease :app:bundleRelease :app:testReleaseUnitTest :app:lintRelease
print 'Outputs: android/app/build/outputs/{apk/release,bundle/release,mapping/release}'
