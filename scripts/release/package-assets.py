#!/usr/bin/env python3
"""Package store artwork, reviewed screenshots, and listing copy; never app binaries."""
import hashlib
import json
from pathlib import Path
import shutil
import tempfile

from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
OUTPUT = ROOT / 'dist/store'
BRAND = ROOT / 'assets/brand/genoa/store'
NAMES = ('01-home', '02-watch', '03-people', '04-scratchpad')


def main():
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    # Build a fresh package so renamed/deleted inputs cannot leave stale assets behind.
    with tempfile.TemporaryDirectory(prefix='genoa-store-', dir=OUTPUT.parent) as temporary:
        package = Path(temporary) / 'store'
        package.mkdir()
        for source, destination in [
            ('google-play-icon-512.png', 'google-play/icon-512.png'),
            ('google-play-feature-1024x500.png', 'google-play/feature-1024x500.png'),
            ('app-icon-1024.png', 'app-store/icon-1024.png'),
        ]:
            target = package / destination
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(BRAND / source, target)
        for platform, folder, dimensions in [
            ('android', 'google-play/phone', (1080, 1920)),
            ('iphone', 'app-store/iphone', (1320, 2868)),
            ('ipad', 'app-store/ipad', (2064, 2752)),
        ]:
            destination = package / folder
            destination.mkdir(parents=True)
            for name in NAMES:
                source = ROOT / 'store/screenshots' / platform / f'{name}.png'
                with Image.open(source) as im:
                    assert im.size == dimensions, (source, im.size)
                    assert im.convert('RGBA').getchannel('A').getextrema() == (255, 255), source
                    im.convert('RGB').save(destination / source.name, optimize=True,
                                           icc_profile=im.info.get('icc_profile'))
                assert (destination / source.name).stat().st_size < 8 * 1024 * 1024, source
        limits = {'google-play-title.txt': 30, 'google-play-short-description.txt': 80,
                  'app-store-name.txt': 30, 'app-store-subtitle.txt': 30,
                  'app-store-keywords.txt': 100, 'app-store-promotional-text.txt': 170,
                  'description.txt': 4000, 'android-release-notes.txt': 500}
        for name, limit in limits.items():
            assert len((ROOT / 'store/en-US' / name).read_text().strip()) <= limit, name
        shutil.copytree(ROOT / 'store/en-US', package / 'en-US')
        shutil.copy2(ROOT / 'store/privacy-data-worksheet.md', package)
        shutil.copy2(ROOT / 'store/demo/README.md', package / 'demo-media-provenance.md')
        files = [{'path': str(p.relative_to(package)), 'bytes': p.stat().st_size,
                  'sha256': hashlib.sha256(p.read_bytes()).hexdigest()}
                 for p in sorted(package.rglob('*')) if p.is_file()]
        (package / 'manifest.json').write_text(json.dumps({'files': files}, indent=2) + '\n')
        (package / 'SHA256SUMS.txt').write_text(''.join(
            f"{f['sha256']}  {f['path']}\n" for f in files))
        if OUTPUT.exists():
            shutil.rmtree(OUTPUT)
        shutil.move(package, OUTPUT)
    print(f'Packaged 12 reviewed screenshots, store artwork and listing copy: {OUTPUT}')


if __name__ == '__main__':
    main()
