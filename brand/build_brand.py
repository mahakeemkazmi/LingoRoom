"""Builds the Lingo Room logo files and Android launcher icons from one definition.

Run from the repository root:  python3 brand/build_brand.py
Needs: fontTools, rsvg-convert, the Roboto variable font and Noto Sans CJK Bold (paths below).
Text is converted to outlines, so the SVG files do not depend on installed fonts.
"""
import os
import subprocess

from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
from fontTools.ttLib import TTFont
from fontTools.varLib import instancer

ROBOTO = "/usr/share/fonts/truetype/google-fonts/ofl/roboto/Roboto[wdth,wght].ttf"
NOTO_CJK = "/usr/share/fonts/opentype/noto/NotoSansCJK-Bold.ttc"

PURPLE, PURPLE_LIGHT, BLUE, BLUE_DEEP = "#520380", "#7B1FC4", "#00B4FF", "#0A86E8"
NAVY, LAVENDER, WHITE, YELLOW = "#171B38", "#F5F3FF", "#FFFFFF", "#FFB025"

roboto = instancer.instantiateVariableFont(TTFont(ROBOTO), {"wght": 700, "wdth": 100})
noto = TTFont(NOTO_CJK, fontNumber=2)


def glyph_path(font, char, x, baseline, size):
    """Outline of one character as SVG path data, placed with its left edge at x and its baseline at baseline."""
    glyphs = font.getGlyphSet()
    name = font.getBestCmap()[ord(char)]
    scale = size / font["head"].unitsPerEm
    pen = SVGPathPen(glyphs, ntos=lambda v: ("%.1f" % v).rstrip("0").rstrip("."))
    glyphs[name].draw(TransformPen(pen, (scale, 0, 0, -scale, x, baseline)))
    return pen.getCommands(), glyphs[name].width * scale


def text_path(font, text, x, baseline, size):
    parts = []
    for char in text:
        if char == " ":
            x += size * 0.25
            continue
        data, advance = glyph_path(font, char, x, baseline, size)
        parts.append(data)
        x += advance
    return " ".join(parts), x


# the mark, on a 512 x 512 canvas
PURPLE_BUBBLE = "M200 50C288.4 50 360 112.7 360 190S288.4 330 200 330c-18.600 0-36.500-2.800-53.100-7.900L70 372l25.300-76.800C61.100 269.600 40 232 40 190 40 112.700 111.600 50 200 50Z"
BLUE_BUBBLE = "M330 170c82.800 0 150 58.200 150 130 0 36.600-17.400 69.700-45.400 93.400L455 462l-72.100-38.900C366.300 427.600 348.500 430 330 430c-82.800 0-150-58.200-150-130s67.200-130 150-130Z"
WAVE_BARS = [(86, 44), (118, 96), (150, 140), (182, 96), (214, 44)]   # x, height; centred on y = 170
letter_a, _ = glyph_path(roboto, "A", 228, 352, 150)
letter_wen, _ = glyph_path(noto, "文", 322, 372, 118)


def wave(color):
    return "".join('<line x1="%d" y1="%.0f" x2="%d" y2="%.0f" stroke="%s" stroke-width="16" stroke-linecap="round"/>' % (x, 170 - h / 2, x, 170 + h / 2, color) for x, h in WAVE_BARS)


def mark(mode="colour", ink=NAVY):
    """The two speech bubbles. mode: colour, or mono (one flat colour, for stamps and themed icons)."""
    if mode == "mono":
        knock = WHITE if ink != WHITE else NAVY
        return ('<path d="%s" fill="%s"/>%s<path d="%s" fill="%s" stroke="%s" stroke-width="16" stroke-linejoin="round"/>'
                '<path d="%s" fill="%s"/><path d="%s" fill="%s"/>') % (PURPLE_BUBBLE, ink, wave(knock), BLUE_BUBBLE, ink, knock, letter_a, knock, letter_wen, knock)
    return ('<defs><linearGradient id="gp" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="%s"/><stop offset="1" stop-color="%s"/></linearGradient>'
            '<linearGradient id="gb" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="%s"/><stop offset="1" stop-color="%s"/></linearGradient></defs>'
            '<path d="%s" fill="url(#gp)"/>%s<path d="%s" fill="url(#gb)" stroke="%s" stroke-width="16" stroke-linejoin="round"/>'
            '<path d="%s" fill="%s"/><path d="%s" fill="%s"/>') % (PURPLE_LIGHT, PURPLE, BLUE, BLUE_DEEP, PURPLE_BUBBLE, wave(WHITE), BLUE_BUBBLE, WHITE, letter_a, WHITE, letter_wen, WHITE)


def svg(width, height, body, title):
    return ('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 %d %d" width="%d" height="%d" role="img" aria-label="%s"><title>%s</title>%s</svg>\n'
            % (width, height, width, height, title, title, body))


def horizontal(lingo, room, mode="colour", ink=NAVY, background=None):
    word_lingo, end = text_path(roboto, "Lingo", 250, 150, 132)
    word_room, end = text_path(roboto, "Room", end + 30, 150, 132)
    width = int(end + 30)
    body = ""
    if background:
        body += '<rect width="%d" height="220" fill="%s"/>' % (width, background)
    body += '<g transform="translate(16 12) scale(0.4)">%s</g>' % mark(mode, ink)
    body += '<path d="%s" fill="%s"/><path d="%s" fill="%s"/>' % (word_lingo, lingo, word_room, room)
    return svg(width, 220, body, "Lingo Room")


def write(path, content):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as handle:
        handle.write(content)


def png(svg_path, png_path, size=None, width=None):
    os.makedirs(os.path.dirname(png_path), exist_ok=True)
    args = ["rsvg-convert", svg_path, "-o", png_path]
    if size:
        args += ["-w", str(size), "-h", str(size)]
    if width:
        args += ["-w", str(width)]
    subprocess.run(args, check=True)


out = "brand"
files = {
    "logo-mark.svg": svg(512, 512, mark(), "Lingo Room"),
    "logo-mark-mono-navy.svg": svg(512, 512, mark("mono", NAVY), "Lingo Room"),
    "logo-mark-mono-white.svg": svg(512, 512, '<rect width="512" height="512" fill="%s"/>' % NAVY + mark("mono", WHITE), "Lingo Room"),
    "logo-horizontal.svg": horizontal(PURPLE, NAVY),
    "logo-horizontal-dark.svg": horizontal("#C9A4F0", WHITE, background=NAVY),
    "logo-horizontal-mono-navy.svg": horizontal(NAVY, NAVY, "mono", NAVY),
    "logo-horizontal-mono-white.svg": horizontal(WHITE, WHITE, "mono", WHITE, background=NAVY),
    # app icon: the mark on lavender, with rounded corners
    "app-icon.svg": svg(512, 512, '<rect width="512" height="512" rx="112" fill="%s"/><g transform="translate(51 51) scale(0.8)">%s</g>' % (LAVENDER, mark()), "Lingo Room app icon"),
}
for name, content in files.items():
    write(os.path.join(out, name), content)
    if name in ("logo-mark.svg", "app-icon.svg"):
        png(os.path.join(out, name), os.path.join(out, "png", name.replace(".svg", ".png")), size=1024)
    elif name.startswith("logo-horizontal"):
        png(os.path.join(out, name), os.path.join(out, "png", name.replace(".svg", ".png")), width=1600)

# Android launcher icons
res = "app/src/main/res"
tmp = os.path.join(out, ".tmp")
# adaptive icon layers are 108dp, with the artwork inside the central 66dp safe zone
write(os.path.join(tmp, "foreground.svg"), svg(512, 512, '<g transform="translate(128 128) scale(0.5)">%s</g>' % mark(), "foreground"))
write(os.path.join(tmp, "monochrome.svg"), svg(512, 512, '<g transform="translate(128 128) scale(0.5)">%s</g>' % mark("mono", NAVY).replace(NAVY, "#000000"), "monochrome"))
write(os.path.join(tmp, "legacy.svg"), svg(512, 512, '<rect width="512" height="512" rx="112" fill="%s"/><g transform="translate(51 51) scale(0.8)">%s</g>' % (LAVENDER, mark()), "legacy"))
write(os.path.join(tmp, "round.svg"), svg(512, 512, '<circle cx="256" cy="256" r="256" fill="%s"/><g transform="translate(84 84) scale(0.67)">%s</g>' % (LAVENDER, mark()), "round"))
for density, factor in (("mdpi", 1), ("hdpi", 1.5), ("xhdpi", 2), ("xxhdpi", 3), ("xxxhdpi", 4)):
    folder = os.path.join(res, "mipmap-" + density)
    png(os.path.join(tmp, "foreground.svg"), os.path.join(folder, "ic_launcher_foreground.png"), size=int(108 * factor))
    png(os.path.join(tmp, "monochrome.svg"), os.path.join(folder, "ic_launcher_monochrome.png"), size=int(108 * factor))
    png(os.path.join(tmp, "legacy.svg"), os.path.join(folder, "ic_launcher.png"), size=int(48 * factor))
    png(os.path.join(tmp, "round.svg"), os.path.join(folder, "ic_launcher_round.png"), size=int(48 * factor))
for name in os.listdir(tmp):
    os.remove(os.path.join(tmp, name))
os.rmdir(tmp)

# the mark as an Android vector drawable (flat colours), used on the loading and splash screens
bars = "".join('    <path android:pathData="M%d,%.0fL%d,%.0f" android:strokeColor="#FFFFFF" android:strokeWidth="16" android:strokeLineCap="round"/>\n' % (x, 170 - h / 2, x, 170 + h / 2) for x, h in WAVE_BARS)
write(os.path.join(res, "drawable", "app_icon_vector.xml"), '''<?xml version="1.0" encoding="utf-8"?>
<!-- Generated by brand/build_brand.py. Do not edit by hand. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="160dp"
    android:height="160dp"
    android:viewportWidth="512"
    android:viewportHeight="512">
    <path android:pathData="%s" android:fillColor="%s"/>
%s    <path android:pathData="%s" android:fillColor="%s" android:strokeColor="#FFFFFF" android:strokeWidth="16" android:strokeLineJoin="round"/>
    <path android:pathData="%s" android:fillColor="#FFFFFF"/>
    <path android:pathData="%s" android:fillColor="#FFFFFF"/>
</vector>
''' % (PURPLE_BUBBLE, PURPLE, bars, BLUE_BUBBLE, BLUE, letter_a, letter_wen))
print("brand files written")
