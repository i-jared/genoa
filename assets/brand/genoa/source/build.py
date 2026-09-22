#!/usr/bin/env python3
"""Rebuild Genoa artwork from one silhouette and a licensed, outlined wordmark.

Requires Python 3, Pillow, fontTools and rsvg-convert. No network is used.
Run from any directory. --android-res PATH copies generated Android resources.
"""
from pathlib import Path
import argparse
import hashlib
import json
import shutil
import subprocess
import xml.etree.ElementTree as ET

from PIL import Image
from fontTools.ttLib import TTFont
from fontTools.varLib.instancer import instantiateVariableFont
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
from fontTools.pens.boundsPen import BoundsPen

OUT = Path(__file__).resolve().parents[1]
SPEC = json.loads((OUT / "source/brand.json").read_text())
C = SPEC["colors"]
D = SPEC["mark"]["path"]
NS = "http://schemas.android.com/apk/res/android"


def write(path, content):
    path = OUT / path
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content + "\n")
    return path


def svg(w, h, content, label="Genoa"):
    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="{w}" height="{h}" '
            f'viewBox="0 0 {w} {h}" role="img" aria-label="{label}">'
            f'<title>{label}</title>{content}</svg>')


def rect(w, h, color, x=0, y=0, radius=0):
    return f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="{radius}" fill="{color}"/>'


def mark(x, y, size, color):
    return f'<path fill="{color}" transform="translate({x} {y}) scale({size/128})" d="{D}"/>'


def outline_wordmark():
    cfg = SPEC["wordmark"]
    font = instantiateVariableFont(TTFont(OUT / "source" / cfg["font"]), cfg["axes"], inplace=False)
    glyphs, cmap = font.getGlyphSet(), font.getBestCmap()
    pen = SVGPathPen(glyphs)
    bounds = BoundsPen(glyphs)
    x = 0
    for index, char in enumerate("Genoa"):
        glyph = glyphs[cmap[ord(char)]]
        if index:
            x += cfg["pairAdjustments"].get("Genoa"[index-1:index+1], 0)
        transform = (1, 0, 0, -1, x, 0)
        glyph.draw(TransformPen(pen, transform))
        glyph.draw(TransformPen(bounds, transform))
        x += glyph.width + cfg["trackingUnits"]
    return pen.getCommands(), bounds.bounds


WORD, (X0, Y0, X1, Y1) = outline_wordmark()
RATIO = (X1-X0)/(Y1-Y0)


def wordmark(x, y, height, color):
    scale = height/(Y1-Y0)
    return (f'<g transform="translate({x} {y}) scale({scale}) translate({-X0} {-Y0})">'
            f'<path fill="{color}" d="{WORD}"/></g>')


def lockup(x, y, width, color):
    # 96-unit mark beside an 80-unit wordmark; fixed optical alignment.
    natural = 122 + RATIO*80
    return (f'<g transform="translate({x} {y}) scale({width/natural})">'
            f'{mark(0, 0, 96, color)}{wordmark(122, 9, 80, color)}</g>')


def raster(src, destination, width=None, opaque=False):
    destination = OUT / destination
    destination.parent.mkdir(parents=True, exist_ok=True)
    cmd = ["rsvg-convert", str(OUT / src), "-o", str(destination)]
    if width:
        cmd.extend(["-w", str(width)])
    subprocess.run(cmd, check=True)
    if opaque:
        with Image.open(destination) as im:
            im.convert("RGB").save(destination)
    return destination


def vector(path, viewport, size, scale, offset, color):
    return write(path, f'<vector xmlns:android="{NS}" android:width="{size}dp" android:height="{size}dp" '
                 f'android:viewportWidth="{viewport}" android:viewportHeight="{viewport}">\n'
                 f'  <group android:scaleX="{scale}" android:scaleY="{scale}" '
                 f'android:translateX="{offset}" android:translateY="{offset}">\n'
                 f'    <path android:fillColor="{color}" android:pathData="{D}"/>\n'
                 '  </group>\n</vector>')


def build():
    # Every logo SVG is a self-contained path document: no fonts, raster images or CSS.
    for variant in ("teal", "ivory", "white", "black", "ink"):
        color = C[variant]
        write(f"svg/genoa-mark-{variant}.svg", svg(128, 128, mark(0, 0, 128, color)))
        write(f"svg/genoa-wordmark-{variant}.svg", svg(480, 144, wordmark(16, 16, 112, color)))
        write(f"svg/genoa-logo-horizontal-{variant}.svg", svg(640, 160, lockup(20, 12, 600, color)))
        write(f"svg/genoa-logo-stacked-{variant}.svg", svg(400, 320,
              mark(128, 16, 144, color) + wordmark((400-RATIO*72)/2, 204, 72, color)))
        for kind in ("mark", "wordmark", "logo-horizontal", "logo-stacked"):
            for scale in (1, 2, 4):
                file = f"svg/genoa-{kind}-{variant}.svg"
                width = int(ET.parse(OUT / file).getroot().attrib["width"])
                raster(file, f"png/genoa-{kind}-{variant}@{scale}x.png", width*scale)

    # Square store/web art has no baked corner mask; platform supplies its own.
    app = svg(1024, 1024, rect(1024, 1024, C["teal"]) + mark(176, 176, 672, C["ivory"]))
    write("svg/genoa-app-icon.svg", app)
    raster("svg/genoa-app-icon.svg", "store/google-play-icon-512.png", 512)
    raster("svg/genoa-app-icon.svg", "store/app-icon-1024.png", 1024, opaque=True)
    for size in (16, 32, 48, 180, 192, 512):
        raster("svg/genoa-app-icon.svg", f"web/icon-{size}.png", size)
    with Image.open(OUT / "web/icon-48.png") as im:
        im.save(OUT / "web/favicon.ico", sizes=[(16,16), (32,32), (48,48)])
    write("web/favicon.svg", app)
    # The foreground is fully inside the maskable icon's central 80% safe circle.
    write("web/maskable-icon.svg", svg(512, 512,
          rect(512, 512, C["teal"]) + mark(104, 104, 304, C["ivory"])))
    raster("web/maskable-icon.svg", "web/maskable-icon-512.png", 512)
    write("web/safari-pinned-tab.svg", svg(128, 128, mark(0, 0, 128, C["black"])))
    write("web/site.webmanifest", json.dumps({"name":"Genoa", "short_name":"Genoa", "start_url":"/",
          "display":"standalone", "background_color":C["ivory"], "theme_color":C["teal"],
          "icons":[{"src":f"icon-{n}.png", "sizes":f"{n}x{n}", "type":"image/png", "purpose":"any"}
                   for n in (192,512)] + [{"src":"maskable-icon-512.png", "sizes":"512x512",
                   "type":"image/png", "purpose":"maskable"}]}, indent=2))
    write("web/head-snippet.html", '<!-- Paths assume this directory is served at /brand/. -->\n'
          '<link rel="icon" href="/brand/favicon.ico" sizes="16x16 32x32 48x48">\n'
          '<link rel="icon" href="/brand/favicon.svg" type="image/svg+xml">\n'
          '<link rel="apple-touch-icon" href="/brand/icon-180.png">\n'
          '<link rel="mask-icon" href="/brand/safari-pinned-tab.svg" color="#206F60">\n'
          '<link rel="manifest" href="/brand/site.webmanifest">\n'
          '<meta name="theme-color" content="#206F60">')

    # Native assets share the identical curve geometry. A 56-unit mark keeps even
    # the conversation tail inside the central 66-unit circular adaptive safe zone.
    vector("android/res/drawable/genoa_launcher_foreground.xml",108,108,0.4375,24.75,C["ivory"])
    vector("android/res/drawable/genoa_launcher_monochrome.xml",108,108,0.4375,24.75,C["white"])
    vector("android/res/drawable/genoa_brand_mark.xml",128,48,1,0,C["teal"])
    vector("android/res/drawable/genoa_notification.xml",24,24,0.171875,1,C["white"])
    write("android/res/values/genoa_brand_colors.xml", '<resources>\n'
          '    <color name="genoa_launcher_background">#206F60</color>\n</resources>')
    # Older Android versions ignore the monochrome child; the same resource
    # supports themed icons on API 33+ without divergent qualifier copies.
    adaptive = (f'<adaptive-icon xmlns:android="{NS}">\n'
                '    <background android:drawable="@color/genoa_launcher_background"/>\n'
                '    <foreground android:drawable="@drawable/genoa_launcher_foreground"/>\n'
                '    <monochrome android:drawable="@drawable/genoa_launcher_monochrome"/>\n</adaptive-icon>')
    for filename in ("ic_launcher.xml", "ic_launcher_round.xml"):
        write(f"android/res/mipmap-anydpi-v26/{filename}", adaptive)
    for density, size in (("mdpi",48),("hdpi",72),("xhdpi",96),("xxhdpi",144),("xxxhdpi",192)):
        for name, radius in (("ic_launcher",size*.22),("ic_launcher_round",size/2)):
            write("source/legacy-icon.svg",svg(size,size,rect(size,size,C["teal"],radius=radius)
                  +mark(size*.172,size*.172,size*.656,C["ivory"])))
            raster("source/legacy-icon.svg",f"android/res/mipmap-{density}/{name}.png")
    (OUT / "source/legacy-icon.svg").unlink()

    # No text overlay promises unimplemented product capabilities.
    write("store/google-play-feature.svg", svg(1024,500,rect(1024,500,C["ivory"])
          + mark(92,100,300,C["teal"])+wordmark(452,169,125,C["teal"])))
    raster("store/google-play-feature.svg","store/google-play-feature-1024x500.png",opaque=True)
    write("social/open-graph.svg", svg(1200,630,rect(1200,630,C["ivory"])
          +lockup(130,218,940,C["teal"])))
    raster("social/open-graph.svg","social/open-graph-1200x630.png",opaque=True)
    raster("svg/genoa-app-icon.svg","social/avatar-1024.png",1024,opaque=True)

    # Review plate uses the final assets, not the generated concept.
    plate = rect(1600,1080,C["ivory"])
    plate += lockup(150,114,900,C["teal"])
    plate += rect(310,310,C["teal"],1140,72,68) + mark(1189,121,212,C["ivory"])
    plate += rect(740,300,C["dark"],40,440,24) + lockup(110,521,600,C["ivory"])
    plate += rect(740,300,"#FFFFFF",820,440,24) + lockup(890,521,600,C["black"])
    for i, size in enumerate((24,32,48,64,96)):
        plate += mark(96+i*120,863-size/2,size,C["teal"])
    for i,(bg,fg) in enumerate(((C["teal"],C["ivory"]),("#DCEBE3",C["teal"]),("#9AD4BE",C["dark"]))):
        plate += rect(112,112,bg,960+i*160,800,56)+mark(978+i*160,818,76,fg)
    for i,key in enumerate(("teal","ivory","ink","dark")):
        plate += rect(344,12,C[key],52+i*384,996,6)
    write("preview/brand-board.svg",svg(1600,1080,plate,"Genoa logo: primary, reversed, monochrome and small-size applications"))
    raster("preview/brand-board.svg","preview/brand-board.png")
    masks = rect(1000,360,C["ivory"])
    for i,(shape,bg,fg) in enumerate((("circle",C["teal"],C["ivory"]),
                                    ("round",C["teal"],C["ivory"]),
                                    ("circle","#DCEBE3",C["teal"]),
                                    ("round",C["dark"],"#9AD4BE"))):
        clip = '<circle cx="54" cy="54" r="36"/>' if shape == "circle" else '<rect x="18" y="18" width="72" height="72" rx="18"/>'
        masks += (f'<g transform="translate({20+i*245} 60) scale(2.15)">'
                  f'<defs><clipPath id="mask{i}">{clip}</clipPath></defs><g clip-path="url(#mask{i})">'
                  f'{rect(108,108,bg)}{mark(24.75,24.75,56,fg)}</g></g>')
    write("preview/android-masks.svg",svg(1000,360,masks,"Genoa adaptive circle, rounded square and themed icon previews"))
    raster("preview/android-masks.svg","preview/android-masks.png")
    write("source/wordmark-outlined.svg", svg(480,144,wordmark(16,16,112,C["teal"])))
    write("source/mark.svg", svg(128,128,mark(0,0,128,C["teal"])))


def inventory():
    files = []
    for path in sorted(OUT.rglob("*")):
        if not path.is_file() or path.name in ("asset-manifest.json","SHA256SUMS.txt"):
            continue
        record = {"path":path.relative_to(OUT).as_posix(),"bytes":path.stat().st_size,
                  "sha256":hashlib.sha256(path.read_bytes()).hexdigest()}
        if path.suffix == ".png":
            with Image.open(path) as im:
                record.update(width=im.width,height=im.height,mode=im.mode)
        files.append(record)
    write("asset-manifest.json",json.dumps({"brand":"Genoa","version":SPEC["version"],"files":files},indent=2))
    write("SHA256SUMS.txt","\n".join(f'{f["sha256"]}  {f["path"]}' for f in files))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--android-res",type=Path)
    args = parser.parse_args()
    build()
    if args.android_res:
        if not args.android_res.is_dir():
            parser.error("--android-res must point to an existing Android res directory")
        shutil.copytree(OUT / "android/res", args.android_res, dirs_exist_ok=True)
    inventory()
    print(f"Built Genoa brand assets in {OUT}")
