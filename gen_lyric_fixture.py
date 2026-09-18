#!/usr/bin/env python3
"""Derive a committable unit-test fixture from a real lyrics file.

Run from the repository root::

    python gen_lyric_fixture.py --source "path/to/song.mp3"

Writes an LRC file that keeps the source's *timeline* — every timestamp, every marker
line, and every line's width in title columns — with the words replaced by filler.
``app/src/test/resources/lyrics/blank-marker-shape.lrc`` was produced by it and
``LyricsTimelineUtilsRealFileTest`` asserts against it.

Why the words are not kept: they belong to someone else, and this rule never reads them
anyway. What decides a cue's time is the line's timestamp, the width that decides how
many segments the line is cut into, and how far the next line carrying text is — none of
which is text. A fixture built from those reproduces the song's cue timeline to the
millisecond, which ``--check`` demonstrates by running the same arithmetic over both and
comparing. The hand-written fixtures in ``LyricsTimelineUtilsTest`` cannot stand in for
it: the shape that broke the feature was 44 markers out of 105 lines, a density nobody
writes by hand.

The source may be an audio file with an embedded ID3v2 ``USLT`` frame (the same tag the
application prefers) or a plain ``.lrc`` file.
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent
DEFAULT_OUT = ROOT / "app" / "src" / "test" / "resources" / "lyrics" / "blank-marker-shape.lrc"

# The application's own regex (LyricsUtils.LRC_LINE_REGEX), not a looser one: a line the
# application would not recognise as synced must not slip into the fixture as if it were.
APP_LINE_RX = re.compile(r"^\[(\d{2}):(\d{2})[.:](\d{2,3})\](.*)$")

# Neutral filler, rotated per line so neighbouring lines never read alike. Deliberately
# nothing like a lyric: the fixture is data, and nobody should mistake it for one.
SEED = "示例文本用于测试仅此而已"

TITLE_COLUMNS_WIDE = 3
MAX_COLUMNS_PER_CUE = 30
SEGMENT_DWELL_LIMIT_MS = 4_000
BLANK_GAP_LIMIT_MS = 6_000


def read_embedded_uslt(path: Path) -> str:
    """The ID3v2 ``USLT`` frame, parsed by hand so no third-party library is needed."""
    data = path.read_bytes()
    if data[:3] != b"ID3":
        raise SystemExit(f"{path}: not an ID3v2 file (no 'ID3' header)")
    # Syncsafe size: seven bits per byte.
    size = (data[6] << 21) | (data[7] << 14) | (data[8] << 7) | data[9]
    offset, end, frames = 10, 10 + size, {}
    while offset < end - 10:
        frame_id = data[offset:offset + 4]
        if not frame_id.strip(b"\x00"):
            break
        frame_size = int.from_bytes(data[offset + 4:offset + 8], "big")
        frames.setdefault(frame_id, []).append(data[offset + 10:offset + 10 + frame_size])
        offset += 10 + frame_size
    if b"USLT" not in frames:
        raise SystemExit(f"{path}: no USLT frame")
    raw = frames[b"USLT"][0]
    encoding, body = raw[0], raw[4:]  # encoding byte, then a 3-byte language code
    if encoding in (1, 2):
        # Description and text are separated by a terminator in the same encoding, so the
        # search has to walk two bytes at a time.
        cut = 0
        while cut + 1 < len(body) and not (body[cut] == 0 and body[cut + 1] == 0):
            cut += 2
        return body[cut + 2:].decode("utf-16" if encoding == 1 else "utf-16-be", errors="replace")
    codec = {0: "latin-1", 3: "utf-8"}[encoding]
    cut = body.find(b"\x00")
    return body[cut + 1:].decode(codec, errors="replace")


def read_source(path: Path) -> str:
    if path.suffix.lower() == ".lrc":
        blob = path.read_bytes()
        for codec in ("utf-8-sig", "gb18030"):
            try:
                return blob.decode(codec)
            except UnicodeDecodeError:
                continue
        return blob.decode("utf-8", errors="replace")
    return read_embedded_uslt(path)


def parse_like_the_app(text: str) -> list[tuple[int, str]]:
    """Millisecond timestamp and text per synced line; marker lines survive as empty text.

    A two-digit fraction is centiseconds and a three-digit one milliseconds, which the
    application distinguishes the same way — getting it wrong shifts the whole song by 10x.
    """
    lines = []
    for raw in text.split("\n"):
        line = raw.strip()
        if not line:
            continue
        match = APP_LINE_RX.match(line)
        if not match:
            continue
        fraction = match.group(3)
        millis = int(fraction) * (10 if len(fraction) == 2 else 1)
        timestamp = int(match.group(1)) * 60_000 + int(match.group(2)) * 1_000 + millis
        lines.append((timestamp, match.group(4).strip()))
    return lines


def title_columns(text: str) -> int:
    total = 0
    for character in text:
        code = ord(character)
        wide = (
            0x1100 <= code <= 0x115F or 0x2E80 <= code <= 0xA4CF or 0xAC00 <= code <= 0xD7A3
            or 0xF900 <= code <= 0xFAFF or 0xFE30 <= code <= 0xFE6F or 0xFF00 <= code <= 0xFF60
            or 0xFFE0 <= code <= 0xFFE6
        )
        total += TITLE_COLUMNS_WIDE if wide else 1
    return total


def segment_count(text: str) -> int:
    if not text:
        return 1
    columns = title_columns(text)
    return 1 if columns <= MAX_COLUMNS_PER_CUE else -(-columns // MAX_COLUMNS_PER_CUE)


def synthetic_text(real_text: str, index: int) -> str:
    """Filler with exactly the column count of [real_text], so it cuts the same way."""
    columns = title_columns(real_text)
    wide, narrow = divmod(columns, TITLE_COLUMNS_WIDE)
    rotated = SEED[index % len(SEED):] + SEED[:index % len(SEED)]
    return (rotated * (wide // len(SEED) + 1))[:wide] + "x" * narrow


def stamp(millis: int) -> str:
    return f"[{millis // 60_000:02d}:{millis % 60_000 // 1_000:02d}.{millis % 1_000 // 10:02d}]"


def build_cues(lines: list[tuple[int, str]], duration_ms: int) -> list[tuple[int, str]]:
    """Mirror of ``buildLyricCues``: a marker clears the title only across a real gap."""
    cues = []
    for index, (start, body) in enumerate(lines):
        if not body:
            following = next((t for t, b in lines[index + 1:] if b), None)
            if following is None or following - start < BLANK_GAP_LIMIT_MS:
                continue
        count = segment_count(body)
        # ``lineEndMs``, verbatim: a successor's timestamp, else the track duration, else one
        # dwell limit per segment. Writing ``max(duration, start + limit)`` instead looks
        # harmless and moves the last cue of a track whose tail is shorter than a segment.
        if index + 1 < len(lines):
            end = lines[index + 1][0]
        elif duration_ms > start:
            end = duration_ms
        else:
            end = start + SEGMENT_DWELL_LIMIT_MS * count
        dwell = min(max(end - start, 0) // count, SEGMENT_DWELL_LIMIT_MS)
        cues.extend((start + dwell * segment, body) for segment in range(count))
    return cues


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__ and __doc__.splitlines()[0])
    parser.add_argument("--source", required=True, type=Path, help="audio file with a USLT tag, or a .lrc file")
    parser.add_argument("--out", type=Path, default=DEFAULT_OUT, help=f"fixture to write (default: {DEFAULT_OUT.name})")
    parser.add_argument("--duration-ms", type=int, default=265_384,
                        help="track duration of the source; only the last line consults it")
    parser.add_argument("--check", action="store_true",
                        help="also prove the fixture's cue times equal the source's")
    args = parser.parse_args()

    text = read_source(args.source)
    real = parse_like_the_app(text)
    if not real:
        raise SystemExit("no synced lines found")

    unmatched = [l for l in text.splitlines() if l.strip() and not APP_LINE_RX.match(l.strip())]
    markers = sum(1 for _, body in real if not body)
    print(f"source : {len(real)} synced lines | {markers} markers | "
          f"{len(unmatched)} lines the app would not treat as synced")

    bodies = [synthetic_text(body, i) if body else "" for i, (_, body) in enumerate(real)]
    assert all(title_columns(s) == title_columns(b) for s, (_, b) in zip(bodies, real)), "column drift"

    args.out.parent.mkdir(parents=True, exist_ok=True)
    with args.out.open("w", encoding="utf-8", newline="\n") as handle:
        handle.write("".join(stamp(t) + body + "\n" for (t, _), body in zip(real, bodies)))

    cues = build_cues(list(zip((t for t, _ in real), bodies)), args.duration_ms)
    empty = [i for i, (_, body) in enumerate(cues) if not body]
    print(f"fixture: {args.out}")
    print(f"         {len(real)} lines | {len(cues)} cues | {len(empty)} clearing markers "
          f"at cue {empty} ({[t for t, b in cues if not b]} ms)")

    if args.check:
        source_cues = build_cues(real, args.duration_ms)
        same = [t for t, _ in source_cues] == [t for t, _ in cues]
        print(f"check  : source builds {len(source_cues)} cues, fixture {len(cues)}, "
              f"timestamps identical: {same}")
        if not same:
            for index, (a, b) in enumerate(zip(source_cues, cues)):
                if a[0] != b[0]:
                    print(f"         first difference at cue {index}: {a[0]} vs {b[0]}")
                    break
        return 0 if same else 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
