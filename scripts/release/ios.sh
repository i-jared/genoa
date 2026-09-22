#!/bin/zsh
set -euo pipefail
repo_root="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$repo_root"
release_dir="$repo_root/dist/ios"
case "${1:-}" in
  ""|--unsigned|--cloud-sign) ;;
  *) print -u2 'Usage: scripts/release/ios.sh [--unsigned|--cloud-sign]'; exit 2 ;;
esac
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
# Organizer needs this metadata even when export uses cloud-managed signing.
archive_info = Path(os.environ['GENOA_ARCHIVE_ENTITLEMENTS']).parent / 'Genoa.xcarchive/Info.plist'
info = plistlib.loads(archive_info.read_bytes())
info['ApplicationProperties']['Team'] = os.environ['GENOA_APPLE_TEAM']
archive_info.write_bytes(plistlib.dumps(info))
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
import hashlib, os, plistlib, shutil, subprocess, tempfile
from pathlib import Path
with tempfile.TemporaryDirectory(prefix='genoa-ipa-check-') as temporary:
    subprocess.run(['ditto', '-x', '-k', os.environ['GENOA_EXPORTED_IPA'], temporary], check=True)
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
    # Keep Organizer's archive consistent with the distribution-signed exported app.
    output = Path(os.environ['GENOA_EXPORTED_IPA']).parents[1]
    archive = output / 'Genoa.xcarchive'
    archived_app = archive / 'Products/Applications/Genoa.app'
    shutil.rmtree(archived_app)
    shutil.copytree(app, archived_app, symlinks=True)
    info_path = archive / 'Info.plist'
    info = plistlib.loads(info_path.read_bytes())
    info['ApplicationProperties']['Team'] = os.environ['GENOA_APPLE_TEAM']
    details = subprocess.run(['codesign', '-d', '-vv', str(app)], check=True, capture_output=True, text=True)
    info['ApplicationProperties']['SigningIdentity'] = next(
        line.removeprefix('Authority=') for line in details.stderr.splitlines() if line.startswith('Authority='))
    info_path.write_bytes(plistlib.dumps(info))
    subprocess.run(['codesign', '--verify', '--deep', '--strict', str(archived_app)], check=True)
    def uuids(path):
        result = subprocess.check_output(['dwarfdump', '--uuid', str(path)], text=True)
        return {line.split()[1] for line in result.splitlines() if line.startswith('UUID:')}
    assert uuids(app / 'Genoa') == uuids(archive / 'dSYMs/Genoa.app.dSYM'), 'App/dSYM UUID mismatch'
    shutil.move(os.environ['GENOA_EXPORTED_IPA'], output / 'Genoa.ipa')
    shutil.make_archive(str(output / 'Genoa-dSYMs'), 'zip', archive, 'dSYMs')
    shutil.rmtree(output / 'export')
    for name in ['ExportOptions.plist', 'ArchiveEntitlements.plist']:
        (output / name).unlink(missing_ok=True)
    paths = sorted(p for p in output.rglob('*') if p.is_file() and p.name != 'SHA256SUMS.txt')
    (output / 'SHA256SUMS.txt').write_text(''.join(
        f'{hashlib.sha256(p.read_bytes()).hexdigest()}  {p.relative_to(output)}\n' for p in paths))
print('Verified App Store signature, app.genoa identity, team, production push and matching symbols.')
PY
print 'Outputs: dist/ios/Genoa.ipa, Genoa.xcarchive, Genoa-dSYMs.zip, SHA256SUMS.txt'
