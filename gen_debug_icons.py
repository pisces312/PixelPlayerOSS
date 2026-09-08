"""Generate red-tinted launcher icons for the debug source set of PixelPlayerOSS.

Run from anywhere:  python gen_debug_icons.py

The debug variant already uses a separate application id (`.debug`) and app name
(`PixelPlayerOSS [D]`). This script adds the visual half of that separation by
writing a red-tinted copy of the adaptive icon background into
`app/src/debug/res`, so debug and release builds are distinguishable on the
launcher without touching the main sources.

Only `ic_launcher_background` and the legacy square/round icons are written. The
foreground and the `mipmap-anydpi-v26` adaptive icon XML are inherited from the
main source set, so nothing is duplicated unnecessarily.
"""

from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parent
MAIN_RES = ROOT / "app" / "src" / "main" / "res"
DEBUG_RES = ROOT / "app" / "src" / "debug" / "res"

# Legacy launcher icon edge length per density bucket.
ICON_SIZES = {
    "mdpi": 48,
    "hdpi": 72,
    "xhdpi": 96,
    "xxhdpi": 144,
    "xxxhdpi": 192,
}

# Adaptive icon foreground layer is drawn on a 108dp canvas for a 48dp icon.
FOREGROUND_SIZES = {density: size * 108 // 48 for density, size in ICON_SIZES.items()}

FOREGROUND_SRC = MAIN_RES / "mipmap-xxxhdpi" / "ic_launcher_foreground.webp"


def tint_red(image: Image.Image) -> Image.Image:
    """Tint an RGBA image red while preserving its alpha channel."""
    r, g, b, a = image.split()
    r = r.point(lambda i: min(255, int(i * 1.2) + 40))
    g = g.point(lambda i: int(i * 0.3))
    b = b.point(lambda i: int(i * 0.3))
    return Image.merge("RGBA", (r, g, b, a))


def main() -> None:
    for density, size in ICON_SIZES.items():
        out_dir = DEBUG_RES / f"mipmap-{density}"
        out_dir.mkdir(parents=True, exist_ok=True)

        background = (
            Image.open(MAIN_RES / f"mipmap-{density}" / "ic_launcher_background.webp")
            .convert("RGBA")
            .resize((size, size), Image.LANCZOS)
        )
        background = tint_red(background)
        background.save(out_dir / "ic_launcher_background.webp", "WEBP", quality=90)

        # Legacy square icons for launchers that ignore the adaptive icon XML.
        foreground_size = FOREGROUND_SIZES[density]
        foreground = (
            Image.open(FOREGROUND_SRC)
            .convert("RGBA")
            .resize((foreground_size, foreground_size), Image.LANCZOS)
        )
        composed = Image.new("RGBA", (size, size), (0, 0, 0, 0))
        offset = (size - foreground_size) // 2
        composed.paste(background, (0, 0), background)
        composed.paste(foreground, (offset, offset), foreground)
        composed.save(out_dir / "ic_launcher.webp", "WEBP", quality=90)
        composed.save(out_dir / "ic_launcher_round.webp", "WEBP", quality=90)

        print(f"{density}: {size}x{size}")

    print(f"Debug icons written to {DEBUG_RES}")


if __name__ == "__main__":
    main()
