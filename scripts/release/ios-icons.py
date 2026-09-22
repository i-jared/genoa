#!/usr/bin/env python3
"""Build Apple's appearance variants from the existing Genoa vector master."""
import io
import json
from pathlib import Path
import shutil
import subprocess
from PIL import Image

root = Path(__file__).resolve().parents[2]
brand = root / "assets/brand/genoa"
target = root / "ios/Genoa/Resources/Assets.xcassets/AppIcon.appiconset"
master = (brand / "svg/genoa-app-icon.svg").read_text()
shutil.copy2(brand / "store/app-icon-1024.png", target / "image.png")
images = [{"filename": "image.png", "idiom": "universal", "platform": "ios", "size": "1024x1024"}]
for appearance, background, foreground in [("dark", "#17251F", "#F5F3EE"), ("tinted", "#202020", "#F5F5F5")]:
    svg = master.replace("#206F60", background).replace("#F5F3EE", foreground)
    data = subprocess.run(["rsvg-convert", "-w", "1024", "-h", "1024"], input=svg.encode(), capture_output=True, check=True).stdout
    Image.open(io.BytesIO(data)).convert("RGB").save(target / f"{appearance}.png", optimize=True)
    images.append({"filename": f"{appearance}.png", "idiom": "universal", "platform": "ios", "size": "1024x1024", "appearances": [{"appearance": "luminosity", "value": appearance}]})
(target / "Contents.json").write_text(json.dumps({"images": images, "info": {"author": "xcode", "version": 1}}, indent=2) + "\n")
print("Built default, dark and grayscale tinted 1024px iOS icons from the Genoa vector master.")
