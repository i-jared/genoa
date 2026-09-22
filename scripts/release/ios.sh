#!/bin/zsh
set -euo pipefail
repo_root="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$repo_root"
release_dir="${GENOA_IOS_OUTPUT:-$repo_root/artifacts/deployment/ios}"
mkdir -p "$release_dir"
if [[ "${1:-}" == "--unsigned" ]]; then
  xcodebuild -project ios/Genoa.xcodeproj -scheme Genoa -configuration Release \
    -destination 'generic/platform=iOS' -archivePath "$release_dir/Genoa-unsigned.xcarchive" \
    CODE_SIGNING_ALLOWED=NO archive
  print 'Unsigned archive only; it cannot be uploaded to App Store Connect.'
  exit 0
fi
: "${GENOA_APPLE_TEAM:?Set the Apple Developer team authorized for Genoa}"
export GENOA_APPLE_TEAM
export GENOA_EXPORT_OPTIONS="$release_dir/ExportOptions.plist"
python3 - <<'PY'
import os, plistlib
from pathlib import Path
Path(os.environ['GENOA_EXPORT_OPTIONS']).write_bytes(plistlib.dumps({
    'method': 'app-store-connect', 'destination': 'export',
    'teamID': os.environ['GENOA_APPLE_TEAM'], 'signingStyle': 'automatic',
    'manageAppVersionAndBuildNumber': False, 'uploadSymbols': True,
}))
PY
# Cloud export avoids requiring a local development private key. Preserve capabilities
# in an ad-hoc archive signature so Xcode includes them when signing the exported IPA.
if [[ "${1:-}" == "--cloud-sign" ]]; then
  xcodebuild -project ios/Genoa.xcodeproj -scheme Genoa -configuration Release \
    -destination 'generic/platform=iOS' -archivePath "$release_dir/Genoa.xcarchive" \
    CODE_SIGNING_ALLOWED=NO archive
  export GENOA_ARCHIVE_ENTITLEMENTS="$release_dir/ArchiveEntitlements.plist"
  python3 - <<'PY'
import os, plistlib
from pathlib import Path
entitlements = plistlib.loads(Path('ios/Genoa/Resources/Genoa.entitlements').read_bytes())
entitlements['aps-environment'] = 'production'
assert '$(' not in str(entitlements), 'Unresolved signing entitlement'
Path(os.environ['GENOA_ARCHIVE_ENTITLEMENTS']).write_bytes(plistlib.dumps(entitlements))
PY
  codesign --force --sign - --entitlements "$GENOA_ARCHIVE_ENTITLEMENTS" \
    "$release_dir/Genoa.xcarchive/Products/Applications/Genoa.app"
else
  # Provisioning may register the bundle ID and update profiles in the selected team.
  xcodebuild -project ios/Genoa.xcodeproj -scheme Genoa -configuration Release \
    -destination 'generic/platform=iOS' -archivePath "$release_dir/Genoa.xcarchive" \
    DEVELOPMENT_TEAM="$GENOA_APPLE_TEAM" -allowProvisioningUpdates archive
fi
xcodebuild -exportArchive -archivePath "$release_dir/Genoa.xcarchive" \
  -exportOptionsPlist "$GENOA_EXPORT_OPTIONS" -exportPath "$release_dir/export" \
  -allowProvisioningUpdates
export GENOA_EXPORTED_IPA="$release_dir/export/Genoa.ipa"
python3 - <<'PY'
import os, plistlib, subprocess, tempfile, zipfile
from pathlib import Path
with tempfile.TemporaryDirectory(prefix='genoa-ipa-check-') as temporary:
    with zipfile.ZipFile(os.environ['GENOA_EXPORTED_IPA']) as ipa:
        ipa.extractall(temporary)
    app = Path(temporary) / 'Payload/Genoa.app'
    subprocess.run(['codesign', '--verify', '--deep', '--strict', str(app)], check=True)
    entitlements = plistlib.loads(subprocess.check_output(
        ['codesign', '-d', '--entitlements', ':-', str(app)], stderr=subprocess.DEVNULL))
    profile = plistlib.loads(subprocess.check_output(
        ['security', 'cms', '-D', '-i', str(app / 'embedded.mobileprovision')]))
    assert plistlib.loads((app / 'Info.plist').read_bytes())['CFBundleIdentifier'] == 'app.genoa'
    assert entitlements['application-identifier'] == os.environ['GENOA_APPLE_TEAM'] + '.app.genoa'
    assert entitlements['aps-environment'] == 'production'
    assert entitlements['get-task-allow'] is False
    assert profile['TeamIdentifier'] == [os.environ['GENOA_APPLE_TEAM']]
    assert 'ProvisionedDevices' not in profile and not profile.get('ProvisionsAllDevices')
print('Verified App Store signature, app.genoa identity, team and production push entitlement.')
PY
print 'Exported IPA locally. No upload or store submission was performed.'
