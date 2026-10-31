#!/usr/bin/env python3
"""Imports Os Lusíadas from Project Gutenberg eBook #3333 into sources/lusiadas/canto-NN.txt.

Run once from the repo root, on a machine that can reach gutenberg.org:

    python sources/tools/import_gutenberg.py                      # downloads the text
    python sources/tools/import_gutenberg.py --file pg3333.txt    # or reads a copy you downloaded

Each stanza is written as its number on a line, its eight verse lines, and a blank line, which is the format
backend SourceTexts reads. The import refuses a text whose stanza counts don't match source.yaml, or a stanza that
isn't eight lines, so a partial or different edition never lands in the repo. Standard library only.
"""
import argparse
import re
import sys
import urllib.request
from pathlib import Path

URL = "https://www.gutenberg.org/cache/epub/3333/pg3333.txt"
ORDINALS = ["primeiro", "segundo", "terceiro", "quarto", "quinto", "sexto", "sétimo", "oitavo", "nono", "décimo"]
HEADING = re.compile(r"^\s*canto\s+(primeiro|segundo|terceiro|quarto|quinto|sexto|s[ée]timo|oitavo|nono|d[ée]cimo)\s*\.?\s*$",
                     re.IGNORECASE)
LINES_PER_STANZA = 8


def strip_gutenberg(text):
    """Keeps only what lies between the Project Gutenberg start and end markers, if present."""
    start = re.search(r"^\*\*\* ?START OF.*$", text, re.MULTILINE)
    end = re.search(r"^\*\*\* ?END OF.*$", text, re.MULTILINE)
    return text[start.end() if start else 0:end.start() if end else len(text)]


def canto_index(word):
    w = word.lower().replace("setimo", "sétimo").replace("decimo", "décimo")
    return ORDINALS.index(w) + 1


def parse(text):
    """Returns {canto: [[line, ...], ...]} from the Gutenberg text. Raises ValueError on anything unexpected."""
    cantos = {}
    current = None
    blocks = []
    block = []
    numbers = []
    number = None

    def close_block():
        nonlocal block, number
        if block:
            blocks.append(block)
            numbers.append(number)
        block, number = [], None

    def close_canto():
        close_block()
        if current is not None:
            for i, n in enumerate(numbers):
                if n is not None and n != i + 1:
                    raise ValueError(f"canto {current}: stanza numbered {n} is in position {i + 1}")
            cantos[current] = list(blocks)

    for raw in strip_gutenberg(text).splitlines():
        line = raw.strip()
        m = HEADING.match(line)
        if m:
            close_canto()
            current = canto_index(m.group(1))
            if current in cantos:
                raise ValueError(f"canto {current} appears twice")
            blocks, numbers = [], []
            continue
        if current is None:
            continue
        if not line:
            close_block()
        elif re.fullmatch(r"\d+", line):
            close_block()
            number = int(line)
        else:
            block.append(line)
    close_canto()
    return cantos


def check(cantos, counts):
    problems = []
    for c, expected in enumerate(counts, start=1):
        got = cantos.get(c)
        if got is None:
            problems.append(f"canto {c} is missing")
            continue
        if len(got) != expected:
            problems.append(f"canto {c} has {len(got)} stanzas, expected {expected}")
        for i, st in enumerate(got, start=1):
            if len(st) != LINES_PER_STANZA:
                problems.append(f"canto {c} stanza {i} has {len(st)} lines: {st[0][:40]!r}")
    if problems:
        raise ValueError("refusing the import:\n  " + "\n  ".join(problems))


def stanza_counts(source_yaml):
    m = re.search(r"^stanza_counts:\s*\[([^\]]*)\]", source_yaml.read_text(encoding="utf-8"), re.MULTILINE)
    if not m:
        raise ValueError(f"{source_yaml} has no stanza_counts")
    return [int(x) for x in m.group(1).split(",")]


def write(cantos, out_dir):
    for c, stanzas in sorted(cantos.items()):
        body = "\n".join(f"{i}\n" + "\n".join(st) + "\n" for i, st in enumerate(stanzas, start=1))
        (out_dir / f"canto-{c:02d}.txt").write_text(body, encoding="utf-8")


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--file", help="a downloaded copy of the Gutenberg text (default: download it)")
    ap.add_argument("--out", default=str(Path(__file__).resolve().parent.parent / "lusiadas"))
    args = ap.parse_args(argv)
    out = Path(args.out)
    if args.file:
        text = Path(args.file).read_text(encoding="utf-8-sig")
    else:
        with urllib.request.urlopen(URL, timeout=60) as r:
            text = r.read().decode("utf-8-sig")
    cantos = parse(text)
    check(cantos, stanza_counts(out / "source.yaml"))
    write(cantos, out)
    print(f"wrote {len(cantos)} cantos, {sum(len(v) for v in cantos.values())} stanzas to {out}")


if __name__ == "__main__":
    try:
        main()
    except ValueError as e:
        print(e, file=sys.stderr)
        sys.exit(1)
