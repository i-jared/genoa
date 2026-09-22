#!/usr/bin/env python3
"""Assemble store assets from vector exports and real, reviewed native captures."""
import hashlib
import html
import json
from pathlib import Path
import shutil
from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
OUTPUT = ROOT / "artifacts/deployment/package"
LOGS = ROOT / "artifacts/deployment/logs"
BRAND = ROOT / "assets/brand/genoa"
CATALOG = ROOT / "ios/Genoa/Resources/Assets.xcassets"


def copy(source, destination):
    target = OUTPUT / destination
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(source, target)


def screenshot(source, destination, dimensions):
    target = OUTPUT / destination
    target.parent.mkdir(parents=True, exist_ok=True)
    with Image.open(source) as im:
        assert im.size == dimensions, (source, im.size)
        assert im.convert("RGBA").getchannel("A").getextrema() == (255, 255), source
        # Stores require no alpha channel. Pixels, dimensions and color profile are preserved.
        im.convert("RGB").save(target, optimize=True, icc_profile=im.info.get("icc_profile"))
    assert target.stat().st_size < 8 * 1024 * 1024, target


def main():
    OUTPUT.mkdir(parents=True, exist_ok=True)
    copy(BRAND / "store/google-play-icon-512.png", "store/google-play/icon-512.png")
    copy(BRAND / "store/google-play-feature-1024x500.png", "store/google-play/feature-1024x500.png")
    copy(BRAND / "store/app-icon-1024.png", "store/app-store/icon-1024.png")
    for appearance in ["dark", "tinted"]:
        copy(CATALOG / f"AppIcon.appiconset/{appearance}.png", f"platform-icons/ios/{appearance}-1024.png")
    for platform, size in [("iphone", (1320, 2868)), ("ipad", (2064, 2752))]:
        directory = LOGS / f"{platform}-captures"
        count = 0
        for test in json.loads((directory / "manifest.json").read_text()):
            for attachment in test["attachments"]:
                name = attachment["suggestedHumanReadableName"].split("_0_")[0]
                if not name.startswith(("01-", "02-", "03-", "04-")):
                    continue
                assert not attachment["isAssociatedWithFailure"]
                screenshot(directory / attachment["exportedFileName"], f"store/app-store/{platform}/{name}.png", size)
                count += 1
        assert count == 4, platform
    android = LOGS / "android-phone-captures/store-screenshots"
    for name in ["01-home", "02-watch", "03-people", "04-scratchpad"]:
        screenshot(android / f"{name}.png", f"store/google-play/phone/{name}.png", (1080, 1920))
    shutil.copytree(ROOT / "store/en-US", OUTPUT / "store/en-US", dirs_exist_ok=True)
    copy(ROOT / "store/privacy-data-worksheet.md", "store/privacy-data-worksheet.md")
    copy(ROOT / "store/demo/README.md", "store/demo-media-provenance.md")
    shutil.copytree(BRAND, OUTPUT / "brand", dirs_exist_ok=True, ignore=shutil.ignore_patterns(".DS_Store", "__pycache__"))
    shutil.copytree(BRAND / "android/res", OUTPUT / "platform-icons/android/res", dirs_exist_ok=True)
    shutil.copytree(CATALOG, OUTPUT / "platform-icons/ios/Assets.xcassets", dirs_exist_ok=True)
    for file in (ROOT / "android/app/src/main/res/drawable").glob("genoa_*.xml"):
        copy(file, f"platform-icons/android/res/drawable/{file.name}")
    for file in ["PrivacyInfo.xcprivacy", "Genoa.entitlements"]:
        copy(ROOT / "ios/Genoa/Resources" / file, f"ios/{file}")
    limits = {"google-play-title.txt": 30, "google-play-short-description.txt": 80,
              "app-store-name.txt": 30, "app-store-subtitle.txt": 30,
              "app-store-keywords.txt": 100, "app-store-promotional-text.txt": 170,
              "description.txt": 4000, "android-release-notes.txt": 500}
    for name, limit in limits.items():
        assert len((ROOT / "store/en-US" / name).read_text().strip()) <= limit, name
    copy(ROOT / "store/README.md", "README.md")
    groups = []
    for label, folder in [("Google Play · phone", "store/google-play/phone"), ("App Store · iPhone", "store/app-store/iphone"), ("App Store · iPad", "store/app-store/ipad")]:
        cards = "".join(f'<a href="{p.relative_to(OUTPUT)}"><img src="{p.relative_to(OUTPUT)}" alt="{html.escape(p.stem)}"><span>{html.escape(p.stem)}</span></a>' for p in sorted((OUTPUT / folder).glob("*.png")))
        groups.append(f"<h2>{label}</h2><div class=gallery>{cards}</div>")
    page = '''<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>Genoa · Release assets</title>
<style>body{margin:0;background:#f5f3ee;color:#252925;font:16px/1.5 system-ui}main{max-width:1200px;margin:auto;padding:40px 24px}h1,h2{font-family:Georgia,serif}h1{font-size:44px;font-weight:500}h2{margin-top:48px;font-size:26px}a{color:#206f60}header{display:flex;align-items:center;gap:24px}header img{width:88px;border-radius:20px}.notice{padding:16px 20px;background:#fff;border-left:4px solid #206f60;border-radius:4px}.gallery{display:grid;grid-template-columns:repeat(auto-fit,minmax(200px,1fr));gap:18px}.gallery img{width:100%;height:auto;display:block;border:1px solid #ddd}.gallery span{display:block;padding:8px}.icons{display:flex;gap:20px;flex-wrap:wrap}.icons img{width:128px;height:128px}.feature{width:min(100%,640px);height:auto}</style>
<main><header><img src="store/app-store/icon-1024.png" alt="Genoa app icon"><div><h1>Genoa release handoff</h1><p>Android 0.2.8 (12) · iOS 0.1.0 (1)</p></div></header>
<p class="notice">App identity: app.genoa. Signed Android AAB/APK and App Store IPA verified. Icons, store artwork and native screenshots included. Store uploads and physical-device release checks remain.</p>
<p><a href="README.md">Deployment instructions</a> · <a href="verification.json">Verification record</a> · <a href="asset-manifest.json">Asset inventory</a> · <a href="SHA256SUMS.txt">Checksums</a></p>
<h2>App icons</h2><div class="icons"><img src="store/app-store/icon-1024.png" alt="Default"><img src="platform-icons/ios/dark-1024.png" alt="Dark"><img src="platform-icons/ios/tinted-1024.png" alt="Tinted grayscale source"></div>
<h2>Google Play feature graphic</h2><img class="feature" src="store/google-play/feature-1024x500.png" alt="Genoa feature graphic">
<p>Native screenshots show fictional local conversations and generated demo media. No customer content is included.</p>''' + "".join(groups) + "</main></html>"
    (OUTPUT / "preview.html").write_text(page)
    files = []
    for file in sorted(OUTPUT.rglob("*")):
        if not file.is_file() or file.name in ("asset-manifest.json", "SHA256SUMS.txt"):
            continue
        item = {"path": str(file.relative_to(OUTPUT)), "bytes": file.stat().st_size,
                "sha256": hashlib.sha256(file.read_bytes()).hexdigest()}
        if file.suffix.lower() == ".png":
            with Image.open(file) as im:
                item.update(width=im.width, height=im.height, mode=im.mode)
        files.append(item)
    (OUTPUT / "asset-manifest.json").write_text(json.dumps({"files": files}, indent=2) + "\n")
    (OUTPUT / "SHA256SUMS.txt").write_text("".join(f"{f['sha256']}  {f['path']}\n" for f in files))
    print(f"Packaged and validated 12 native screenshots, store artwork, metadata and {len(files)} total files.")


if __name__ == "__main__":
    main()
