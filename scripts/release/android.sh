#!/bin/zsh
set -euo pipefail
repo_root="$(cd "$(dirname "$0")/../.." && pwd)"
variant="${1:---signed}"
case "$variant" in
  --debug|--unsigned)
    unset GENOA_KEYSTORE GENOA_KEYSTORE_PASSWORD GENOA_KEY_ALIAS GENOA_KEY_PASSWORD
    ;;
  --signed)
    : "${GENOA_KEYSTORE:?Set the absolute path to the existing release/upload keystore}"
    : "${GENOA_KEYSTORE_PASSWORD:?Set the keystore password without putting it in command history}"
    : "${GENOA_KEY_ALIAS:?Set the release/upload key alias}"
    : "${GENOA_KEY_PASSWORD:?Set the key password}"
    ;;
  *) print -u2 'Usage: scripts/release/android.sh [--debug|--unsigned|--signed]'; exit 2 ;;
esac
cd "$repo_root/android"
if [[ "$variant" == "--debug" ]]; then
  ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
else
  ./gradlew :app:assembleRelease :app:bundleRelease :app:testReleaseUnitTest :app:lintRelease
fi
# Publish only after all checks succeed; keep signing variants clearly labeled.
python3 - "$repo_root" "$variant" <<'PY'
from pathlib import Path
import hashlib, json, shutil, sys
root, variant = Path(sys.argv[1]), sys.argv[2]
source = root / 'android/app/build/outputs'
output = root / 'dist/android'
output.mkdir(parents=True, exist_ok=True)
build_type = 'debug' if variant == '--debug' else 'release'
metadata = json.loads((source / f'apk/{build_type}/output-metadata.json').read_text())
element, = metadata['elements']
suffix = {'--debug': '-debug', '--unsigned': '-unsigned', '--signed': ''}[variant]
files = [(source / f'apk/{build_type}' / element['outputFile'], f'Genoa{suffix}.apk')]
if build_type == 'release':
    files += [(source / 'bundle/release/app-release.aab', f'Genoa{suffix}.aab'),
              (source / 'mapping/release/mapping.txt', f'mapping{suffix}.txt')]
for path, name in files:
    if not path.is_file():
        raise SystemExit(f'Missing build output: {path}')
for path, name in files:
    temporary = output / f'.{name}.tmp'
    shutil.copy2(path, temporary)
    temporary.replace(output / name)
(output / f'build{suffix}.json').write_text(json.dumps({
    'applicationId': metadata['applicationId'], 'versionName': element['versionName'],
    'versionCode': element['versionCode'], 'variant': variant.removeprefix('--'),
    'files': [name for _, name in files],
}, indent=2) + '\n')
paths = sorted(p for p in output.iterdir() if p.is_file() and p.name != 'SHA256SUMS.txt')
(output / 'SHA256SUMS.txt').write_text(''.join(
    f'{hashlib.sha256(p.read_bytes()).hexdigest()}  {p.name}\n' for p in paths))
print(f"Genoa {element['versionName']} ({element['versionCode']}): {output}")
PY
