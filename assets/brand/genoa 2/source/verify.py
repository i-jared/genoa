#!/usr/bin/env python3
"""Check the handoff's files, geometry, platform exports and integrity offline."""
from pathlib import Path
import hashlib
import io
import json
import subprocess
import xml.etree.ElementTree as ET
from PIL import Image

BASE = Path(__file__).resolve().parents[1]
SPEC = json.loads((BASE / "source/brand.json").read_text())
ANDROID = "{http://schemas.android.com/apk/res/android}"


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def alpha_shape(svg):
    output = subprocess.run(["rsvg-convert", "-w", "1080"],input=svg.encode(),capture_output=True,check=True)
    return Image.open(io.BytesIO(output.stdout)).convert("RGBA")


def in_circle(image, radius):
    # Check every pixel that contributes visibly, allowing half-pixel AA rounding.
    scale = image.width
    alpha = image.getchannel("A")
    for y in range(image.height):
        for x in range(image.width):
            if alpha.getpixel((x,y)) > 127:
                require(((x+.5-scale/2)**2+(y+.5-scale/2)**2)**.5 <= radius*scale+.75,
                        f"Artwork falls outside the safe circle at {x},{y}")


def verify():
    for file in BASE.rglob("*.svg"):
        root = ET.parse(file).getroot()
        for node in root.iter():
            kind = node.tag.rsplit("}",1)[-1]
            require(kind not in ("text","image","script","foreignObject"), f"External/live content: {file}")
            require(not any(k.endswith("href") for k in node.attrib),f"Reference in {file}")

    expected = {
        "store/google-play-icon-512.png":(512,512),
        "store/app-icon-1024.png":(1024,1024),
        "store/google-play-feature-1024x500.png":(1024,500),
        "social/open-graph-1200x630.png":(1200,630),
        "web/maskable-icon-512.png":(512,512),
    }
    expected.update({f"web/icon-{n}.png":(n,n) for n in (16,32,48,180,192,512)})
    for filename, size in expected.items():
        with Image.open(BASE / filename) as im:
            require(im.size == size,f"Wrong dimensions: {filename}")
            require(im.convert("RGBA").getchannel("A").getextrema() == (255,255),f"Not opaque: {filename}")
    require((BASE / "store/google-play-icon-512.png").stat().st_size <= 1024*1024,"Play icon exceeds 1 MB")
    with Image.open(BASE / "store/google-play-feature-1024x500.png") as im:
        require(im.mode == "RGB","Feature graphic must have no alpha channel")
    with Image.open(BASE / "web/favicon.ico") as im:
        require(im.info["sizes"] == {(16,16),(32,32),(48,48)},"Missing ICO sizes")
    for file in (BASE / "png").glob("*.png"):
        with Image.open(file) as im:
            alpha = im.getchannel("A")
            require(alpha.getextrema() == (0,255),f"Missing transparent background: {file}")
            bounds = alpha.getbbox()
            require(bounds and bounds[0]>0 and bounds[1]>0 and bounds[2]<im.width and bounds[3]<im.height,
                    f"Logo touches an export edge: {file}")

    for layer in ("genoa_launcher_foreground", "genoa_launcher_monochrome"):
        root = ET.parse(BASE / f"android/res/drawable/{layer}.xml").getroot()
        group = root[0]
        path = group[0]
        require(path.attrib[ANDROID+"pathData"] == SPEC["mark"]["path"],"Android curve drift")
        offset = group.attrib[ANDROID+"translateX"]
        scale = group.attrib[ANDROID+"scaleX"]
        content = (f'<svg xmlns="http://www.w3.org/2000/svg" width="108" height="108" viewBox="0 0 108 108">'
                   f'<path fill="white" transform="translate({offset} {offset}) scale({scale})" '
                   f'd="{SPEC["mark"]["path"]}"/></svg>')
        in_circle(alpha_shape(content),33/108)
    maskable = ET.parse(BASE / "web/maskable-icon.svg").getroot()
    maskable.remove(maskable.find("{http://www.w3.org/2000/svg}rect"))
    in_circle(alpha_shape(ET.tostring(maskable,encoding="unicode")),.4)
    resources = {f"@{p.parent.name.split('-')[0]}/{p.stem}" for p in (BASE / "android/res").rglob("*") if p.is_file()}
    resources.add("@color/genoa_launcher_background")
    for file in (BASE / "android/res").rglob("*.xml"):
        for node in ET.parse(file).getroot().iter():
            for value in node.attrib.values():
                if value.startswith("@"):
                    require(value in resources,f"Missing Android resource {value}")
    manifest = json.loads((BASE / "asset-manifest.json").read_text())
    for record in manifest["files"]:
        file = BASE / record["path"]
        require(file.is_file(),f"Missing {file}")
        require(hashlib.sha256(file.read_bytes()).hexdigest() == record["sha256"],f"Checksum mismatch: {file}")
    print(f"PASS: {len(manifest['files'])} inventoried assets; SVG paths, PNG/ICO exports, alpha, Android references, adaptive/maskable safe circles and hashes.")


if __name__ == "__main__":
    verify()
