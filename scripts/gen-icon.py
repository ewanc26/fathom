#!/usr/bin/env python3
"""Generate Fathom's app icon and Android adaptive layers.

The mark is the dashboard's own chart: five bottom-aligned bars, the tallest
in mint, on the deep teal-black background. It follows the family's
construction — one centred geometric mark, flat colour, no text — rather than
copying Inkwell's letterform, which belongs to that app.

iOS gets a single opaque 1024x1024 PNG and lets the system mask and tint it.
Android gets 108 dp vector layers with the mark inside the 66 dp safe zone,
plus a monochrome layer for themed icons. Both are drawn from the same
coordinates here, so the two platforms cannot drift.

Standard library only. Usage: python3 scripts/gen-icon.py [--check]
--check exits 1 if the committed files differ from what this writes.
"""
import os
import struct
import sys
import zlib

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

BACKGROUND = (0x0B, 0x14, 0x16)   # FathomBackground, Theme.kt
TEAL = (0x2D, 0xD4, 0xBF)         # FathomTeal
MINT = (0x99, 0xF6, 0xE4)         # FathomMint

SIZE = 1024
BAR_W = 120
GAP = 56
RADIUS = 24
BASELINE = 812
HEIGHTS = (300, 440, 360, 600, 500)   # design space, bottom-aligned
PEAK = 3                              # the bar drawn in mint
LEFT = (SIZE - (len(HEIGHTS) * BAR_W + (len(HEIGHTS) - 1) * GAP)) // 2

# 52 dp is the widest this 5-bar mark can be and still have every corner
# inside the 66 dp safe circle of Android's 108 dp adaptive container, so a
# round launcher mask cannot clip an outer bar.
ANDROID_MARK_W = 52.0
ANDROID_CANVAS = 108.0


def inside(x, y, left, top, width, height, radius):
    """True where (x, y) falls in a rounded rectangle."""
    cx = min(max(x, left + radius), left + width - radius)
    cy = min(max(y, top + radius), top + height - radius)
    return (x - cx) ** 2 + (y - cy) ** 2 <= radius * radius


def png(width, height, rows):
    raw = b"".join(b"\x00" + bytes(row) for row in rows)

    def chunk(tag, data):
        return (struct.pack(">I", len(data)) + tag + data
                + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF))

    return (b"\x89PNG\r\n\x1a\n"
            + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(raw, 9))
            + chunk(b"IEND", b""))


def design_bars():
    bars = []
    for index, height in enumerate(HEIGHTS):
        x = LEFT + index * (BAR_W + GAP)
        y = BASELINE - height
        colour = MINT if index == PEAK else TEAL
        bars.append((x, y, BAR_W, height, colour))
    return bars


def icon_png():
    """The opaque 1024 square, anti-aliased with 4x4 supersampling."""
    bars = design_bars()
    rows = []
    for py in range(SIZE):
        row = bytearray()
        for px in range(SIZE):
            colour = BACKGROUND
            for bx, by, bw, bh, foreground in bars:
                if bx <= px < bx + bw and by <= py < by + bh:
                    hits = 0
                    for sy in range(4):
                        for sx in range(4):
                            if inside(px + (sx + 0.5) / 4, py + (sy + 0.5) / 4,
                                      bx, by, bw, bh, RADIUS):
                                hits += 1
                    if hits:
                        coverage = hits / 16
                        colour = tuple(
                            round(BACKGROUND[channel] * (1 - coverage)
                                  + foreground[channel] * coverage)
                            for channel in range(3)
                        )
                    break
            row.extend(colour)
        rows.append(row)
    return png(SIZE, SIZE, rows)


def number(value):
    text = f"{value:.3f}".rstrip("0").rstrip(".")
    return "0" if text in ("", "-0") else text


def rounded_rect_path(x, y, width, height, radius):
    return (
        f"M{number(x + radius)},{number(y)}"
        f"H{number(x + width - radius)}"
        f"A{number(radius)},{number(radius)} 0 0 1 {number(x + width)},{number(y + radius)}"
        f"V{number(y + height - radius)}"
        f"A{number(radius)},{number(radius)} 0 0 1 {number(x + width - radius)},{number(y + height)}"
        f"H{number(x + radius)}"
        f"A{number(radius)},{number(radius)} 0 0 1 {number(x)},{number(y + height - radius)}"
        f"V{number(y + radius)}"
        f"A{number(radius)},{number(radius)} 0 0 1 {number(x + radius)},{number(y)}Z"
    )


def android_bars():
    total_width = len(HEIGHTS) * BAR_W + (len(HEIGHTS) - 1) * GAP
    scale = ANDROID_MARK_W / total_width
    total_height = max(HEIGHTS) * scale
    offset_x = (ANDROID_CANVAS - ANDROID_MARK_W) / 2
    offset_y = (ANDROID_CANVAS - total_height) / 2
    baseline = offset_y + total_height

    bars = []
    for index, height in enumerate(HEIGHTS):
        bars.append((
            offset_x + index * (BAR_W + GAP) * scale,
            baseline - height * scale,
            BAR_W * scale,
            height * scale,
            RADIUS * scale,
            index,
        ))
    return bars


def android_vector(monochrome):
    paths = []
    for x, y, width, height, radius, index in android_bars():
        if monochrome:
            fill = "#FFFFFF"
        else:
            fill = "#%02X%02X%02X" % (MINT if index == PEAK else TEAL)
        paths.append(
            '    <path\n'
            f'        android:fillColor="{fill}"\n'
            f'        android:pathData="{rounded_rect_path(x, y, width, height, radius)}" />'
        )

    return (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<!-- Generated by scripts/gen-icon.py; edit that file, not this one. -->\n'
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
        '    android:width="108dp"\n'
        '    android:height="108dp"\n'
        '    android:viewportWidth="108"\n'
        '    android:viewportHeight="108">\n'
        + "\n".join(paths) + "\n</vector>\n"
    )


def outputs():
    return {
        os.path.join(ROOT, "iOS/Fathom/Assets.xcassets/AppIcon.appiconset/AppIcon-1024.png"):
            icon_png(),
        os.path.join(ROOT, "Android/app/src/main/res/drawable/ic_launcher_foreground.xml"):
            android_vector(monochrome=False).encode(),
        os.path.join(ROOT, "Android/app/src/main/res/drawable/ic_launcher_monochrome.xml"):
            android_vector(monochrome=True).encode(),
    }


def main():
    check = "--check" in sys.argv[1:]
    stale = []
    for path, data in outputs().items():
        try:
            with open(path, "rb") as handle:
                same = handle.read() == data
        except FileNotFoundError:
            same = False

        if check:
            if not same:
                stale.append(os.path.relpath(path, ROOT))
        elif not same:
            os.makedirs(os.path.dirname(path), exist_ok=True)
            with open(path, "wb") as handle:
                handle.write(data)
            print("wrote", os.path.relpath(path, ROOT))

    if stale:
        print("gen-icon: out of date, run python3 scripts/gen-icon.py: "
              + ", ".join(stale), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
