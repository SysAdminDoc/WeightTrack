#!/usr/bin/env python3
"""Builds the bundled offline food database from the Open Food Facts export.

Run this, not the app. The output is committed, so a normal build needs no network and no
multi-gigabyte download:

    py -3.13 tools/build_offline_foods.py

The export is about 1.2 GB compressed and roughly ten times that once unpacked, so it is read
straight off the wire and never lands on disk. Only the rows that survive filtering are kept.

Which rows survive is the whole design decision. Ranking the world by scan count would hand back
an almost entirely French shelf, because that is where Open Food Facts started and where most of
its scanning still happens. So the quota is per market: the most-scanned products in each of a
handful of places, which is what makes the offline set useful to somebody in Leeds or Ohio rather
than a curiosity.

Data from Open Food Facts, under the Open Database Licence. Attribution ships in the app.
"""

from __future__ import annotations

import csv
import gzip
import hashlib
import io
import os
import re
import sqlite3
import sys
import urllib.request

DUMP_URL = "https://static.openfoodfacts.org/data/en.openfoodfacts.org.products.csv.gz"

OUT = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "app", "src", "main", "assets", "offline_foods.db",
)

# How many products to keep per market. Deliberately modest: this rides in the APK that every
# user downloads, and a shelf of staples earns its megabyte in a way a long tail does not.
QUOTAS = {
    "en": 9000,   # United States, United Kingdom, Canada, Australia, New Zealand, Ireland
    "fr": 4000,
    "de": 2500,
    "es": 2000,
    "it": 1500,
    "other": 3000,
}

MARKETS = {
    "en": ("united states", "united kingdom", "canada", "australia", "new zealand", "ireland"),
    "fr": ("france", "belgium", "switzerland"),
    "de": ("germany", "austria"),
    "es": ("spain", "mexico"),
    "it": ("italy",),
}

# Anything outside this is a data-entry slip rather than a food. Pure fat is 900 per hundred
# grams, so nothing real goes above it by much, and a zero-calorie row carries no information
# worth a barcode lookup.
MAX_KCAL = 950.0
MIN_KCAL = 1.0

# The export is a genuine free-text dump: unbalanced quotes, stray delimiters, very long fields.
# QUOTE_NONE is the only setting that survives it.
csv.field_size_limit(min(sys.maxsize, 2**31 - 1))

SERVING = re.compile(r"([\d.,]+)\s*(g|gram|grams|ml)\b", re.IGNORECASE)


def number(raw: str) -> float | None:
    raw = (raw or "").strip().replace(",", ".")
    if not raw:
        return None
    try:
        value = float(raw)
    except ValueError:
        return None
    # Negatives and infinities exist in the dump. They are not nutrition.
    if value != value or value in (float("inf"), float("-inf")) or value < 0:
        return None
    return value


def serving_grams(raw: str) -> float | None:
    match = SERVING.search(raw or "")
    if not match:
        return None
    grams = number(match.group(1))
    # A serving of half a kilogram is somebody typing the pack weight into the wrong box.
    if grams is None or not (1.0 <= grams <= 1500.0):
        return None
    return grams


def market_of(countries: str) -> str:
    lowered = (countries or "").lower()
    for key, names in MARKETS.items():
        if any(name in lowered for name in names):
            return key
    return "other"


def clean(raw: str, limit: int) -> str:
    # Newlines and tabs get into product names and would corrupt anything downstream.
    text = " ".join((raw or "").split())
    return text[:limit].strip()


def rows(handle):
    reader = csv.DictReader(handle, delimiter="\t", quoting=csv.QUOTE_NONE)
    for row in reader:
        code = clean(row.get("code", ""), 32)
        # Barcodes are digits. The dump holds internal identifiers that are not.
        if not code or not code.isdigit() or not (6 <= len(code) <= 14):
            continue
        name = clean(row.get("product_name", ""), 90)
        if len(name) < 2:
            continue
        kcal = number(row.get("energy-kcal_100g", ""))
        if kcal is None or not (MIN_KCAL <= kcal <= MAX_KCAL):
            continue
        scans = number(row.get("unique_scans_n", "")) or 0.0
        yield (
            int(scans),
            market_of(row.get("countries_en", "")),
            code,
            name,
            clean(row.get("brands", ""), 60) or None,
            kcal,
            number(row.get("proteins_100g", "")),
            number(row.get("carbohydrates_100g", "")),
            number(row.get("fat_100g", "")),
            number(row.get("fiber_100g", "")),
            number(row.get("sugars_100g", "")),
            number(row.get("salt_100g", "")),
            serving_grams(row.get("serving_size", "")),
        )


def harvest() -> dict[str, list[tuple]]:
    """Reads the whole export once, keeping only the best rows per market as it goes."""
    kept: dict[str, list[tuple]] = {key: [] for key in QUOTAS}
    seen: set[str] = set()
    read = 0
    request = urllib.request.Request(DUMP_URL, headers={"User-Agent": "WeightTrack offline food build"})
    with urllib.request.urlopen(request, timeout=120) as response:
        with gzip.GzipFile(fileobj=response) as unzipped:
            handle = io.TextIOWrapper(unzipped, encoding="utf-8", errors="replace", newline="")
            for row in rows(handle):
                read += 1
                if read % 250_000 == 0:
                    print(f"  read {read:,} usable rows", flush=True)
                code = row[2]
                # The same barcode appears more than once. First occurrence wins.
                if code in seen:
                    continue
                seen.add(code)
                kept[row[1]].append(row)
    return kept


def best(kept: dict[str, list[tuple]]) -> list[tuple]:
    chosen = []
    for market, quota in QUOTAS.items():
        bucket = sorted(kept[market], key=lambda row: (-row[0], row[2]))[:quota]
        print(f"  {market}: {len(bucket):,} of {len(kept[market]):,} kept", flush=True)
        chosen.extend(bucket)
    return chosen


def write(chosen: list[tuple]) -> None:
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    if os.path.exists(OUT):
        os.remove(OUT)
    db = sqlite3.connect(OUT)
    db.executescript(
        """
        PRAGMA journal_mode = OFF;
        PRAGMA page_size = 4096;
        CREATE TABLE offline_food (
            barcode TEXT PRIMARY KEY NOT NULL,
            name TEXT NOT NULL,
            brand TEXT,
            kcal REAL NOT NULL,
            protein REAL, carbs REAL, fat REAL, fibre REAL, sugar REAL, salt REAL,
            serving REAL,
            search TEXT NOT NULL,
            scans INTEGER NOT NULL
        );
        """
    )
    db.executemany(
        "INSERT OR IGNORE INTO offline_food "
        "(barcode, name, brand, kcal, protein, carbs, fat, fibre, sugar, salt, serving, search, scans) "
        "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
        [
            (
                row[2], row[3], row[4], row[5], row[6], row[7], row[8], row[9], row[10], row[11],
                row[12],
                # Matched against instead of the display columns, so a search for "kellogg corn"
                # finds a Kellogg's cornflake without the app having to guess at word order.
                f"{row[3]} {row[4] or ''}".lower(),
                row[0],
            )
            for row in chosen
        ],
    )
    # Ordered by scans so a search that stops at the limit stops on the popular ones.
    db.execute("CREATE INDEX idx_offline_food_search ON offline_food (search, scans DESC)")
    db.commit()
    db.execute("VACUUM")
    db.close()
    size = os.path.getsize(OUT)
    # The app keys its unpacked copy on this. Asking the asset manager for the size would not
    # work: a compressed asset has no length until it is unpacked, which is the expensive thing
    # the key exists to avoid.
    digest = hashlib.sha256()
    with open(OUT, "rb") as handle:
        for block in iter(lambda: handle.read(1 << 20), b""):
            digest.update(block)
    with open(OUT + ".id", "w", encoding="utf-8", newline="\n") as handle:
        handle.write(f"{digest.hexdigest()[:16]} {size}\n")
    print(f"wrote {len(chosen):,} products to {OUT} ({size / 1024 / 1024:.1f} MB)")


def main() -> int:
    print(f"reading {DUMP_URL}", flush=True)
    kept = harvest()
    chosen = best(kept)
    if len(chosen) < 1000:
        print("too few products survived filtering; refusing to write a useless asset")
        return 1
    write(chosen)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
