#!/usr/bin/env python3
"""Build the bundled offline place list that backs Serendipity.

Output: ``app/src/main/assets/cities.json`` — the data behind the city picker and the offline
"coordinates -> place name" lookup. Nothing here talks to the network at build or run time.

Two sources, both downloaded once:

* **China, all three levels** (province / city / district) from AreaCity
  (https://github.com/xiangyuecn/AreaCity-JsSpider-StatsGov, MIT). ``ok_geo.csv`` carries the
  centre point (``geo``) and the administrative path (``ext_path``); ``ok_data_level3.csv``
  carries the pinyin used to spell the names in Latin script.
* **Everywhere else** from GeoNames ``cities15000`` (CC-BY 4.0), restricted to cities of 100k+
  people so the asset stays small. Hong Kong, Macao and Taiwan come from the same file: AreaCity
  lists them but publishes no coordinates for them.

See THIRD_PARTY_NOTICES.md for both licences.

The layout is parallel arrays, one entry per place::

    name       display name: the official name at home, the local name abroad
    ascii      Latin spelling, empty when it matches "name"
    country    ISO 3166-1 alpha-2
    lat/lon    centre point
    rank       1 = district (Chinese only, the finest unit bundled), 2 = city, 3 = province/capital
    parent     index of the city a district belongs to, -1 when there is none
    parentName short name of that city, used to qualify the district in the UI ("Huangpu · Shanghai")

Usage:
    python gen_city_catalog.py <cities15000.txt> <ok_geo.csv> <ok_data_level3.csv>

Download the inputs with:
    curl -L -o cities15000.zip https://download.geonames.org/export/dump/cities15000.zip
    unzip cities15000.zip
    curl -L -o ok_geo.7z https://github.com/xiangyuecn/AreaCity-JsSpider-StatsGov/releases/latest/download/ok_geo.csv.7z
    curl -L -o ok_data_level3.7z https://github.com/xiangyuecn/AreaCity-JsSpider-StatsGov/releases/latest/download/ok_data_level3-4.csv.7z
"""

import csv
import json
import os
import sys

csv.field_size_limit(10**9)

HOME_COUNTRY = "CN"
MIN_FOREIGN_POPULATION = 100_000
OUT_PATH = os.path.join("app", "src", "main", "assets", "cities.json")

# Prominence, used to open the picker on a sensible short list and to prefer the more recognizable
# name when several places sit at the same distance.
RANK_REGION = 3
RANK_CITY = 2
RANK_DISTRICT = 1

# AreaCity's province-level bucket for everything outside China; GeoNames covers that instead.
PLACEHOLDER_REGION = "国外"

# Hong Kong, Macao and Taiwan are part of the bundled Chinese data, under the names China uses for
# them. Their GeoNames rows are dropped rather than letting the picker label them with their own
# country codes.
CHINESE_REGIONS = frozenset({"HK", "MO", "TW"})

# GeoNames cities15000.txt column indices (tab separated, no header).
COL_NAME = 1
COL_ASCII = 2
COL_ALTERNATES = 3
COL_LAT = 4
COL_LON = 5
COL_FEATURE_CODE = 7
COL_COUNTRY = 8
COL_POPULATION = 14

# AreaCity's `deep` column, mapped onto our ranks.
RANK_BY_DEPTH = {"0": RANK_REGION, "1": RANK_CITY, "2": RANK_DISTRICT}


# Administrative suffixes, longest first, with the pinyin syllables they are spelled with.
SUFFIXES = (
    ("特别行政区", ["te", "bie", "xing", "zheng", "qu"]),
    ("自治区", ["zi", "zhi", "qu"]),
    ("自治州", ["zi", "zhi", "zhou"]),
    ("自治县", ["zi", "zhi", "xian"]),
    ("自治旗", ["zi", "zhi", "qi"]),
    ("新区", ["xin", "qu"]),
    ("林区", ["lin", "qu"]),
    ("特区", ["te", "qu"]),
    ("矿区", ["kuang", "qu"]),
    ("地区", ["di", "qu"]),
    ("省", ["sheng"]),
    ("市", ["shi"]),
    ("区", ["qu"]),
    ("县", ["xian"]),
    ("旗", ["qi"]),
    ("盟", ["meng"]),
)


def has_cjk(text):
    return any("\u4e00" <= character <= "\u9fff" for character in text)


def chinese_name(row):
    """The Chinese name of a place in Hong Kong, Macao or Taiwan, from GeoNames' alias list.

    The shortest alias is the plain name: the list also carries administrative and historical
    forms ("元朗", "元朗新墟", "元朗舊墟"). Traditional spellings are kept as they are — that is how
    those places are written. ``None`` when the source knows the place only in Latin script, which
    is not enough for a list read inside China.
    """
    aliases = [a for a in row[COL_ALTERNATES].split(",") if len(a) > 1 and has_cjk(a)]
    return min(aliases, key=len) if aliases else None


def latin(name, ext_name, pinyin):
    """Pinyin to the spelling the prompt uses: "dong cheng" -> "Dongcheng".

    One capital for the whole name, not one per syllable: "Shanghai", not "ShangHai".

    AreaCity spells the suffix out too whenever it could not lift it off the name ("浦东新区" is
    "pu dong xin qu" while "黄浦区" is just "huang pu"), which would give "Pudongxinqu". The
    suffix is therefore cut again — but only in that case, because a name that merely happens to
    end in the same sound keeps it ("涿州市" is "zhuo zhou", not "Zhuo").
    """
    syllables = pinyin.split()
    if name == ext_name:
        for suffix, tail in SUFFIXES:
            if name.endswith(suffix) and syllables[-len(tail) :] == tail and len(syllables) > len(tail):
                syllables = syllables[: -len(tail)]
                break
    return "".join(syllables).capitalize()


def read_csv(path):
    with open(path, encoding="utf-8-sig", newline="") as handle:
        reader = csv.reader(handle)
        header = {column: index for index, column in enumerate(next(reader))}
        return header, list(reader)


def load_china(geo_path, level3_path):
    """Every province, city and district of China, as a flat list.

    Two kinds of row are dropped because they repeat the level above them and would otherwise
    show up twice in the picker:

    * the "city" row of a municipality (北京市 under 北京市);
    * the district row of a city that has no districts (东莞市 under 东莞市), which also covers
      Hong Kong and Macao, whose only entries are the special administrative region itself.
    """
    level3_header, level3_rows = read_csv(level3_path)
    names = {
        row[level3_header["id"]]: (
            row[level3_header["name"]],
            row[level3_header["ext_name"]],
            row[level3_header["pinyin"]],
        )
        for row in level3_rows
        if len(row) > level3_header["pinyin"]
    }

    geo_header, geo_rows = read_csv(geo_path)
    places = []
    parent_of = {}
    for row in geo_rows:
        if len(row) <= max(geo_header["geo"], geo_header["ext_path"]):
            continue
        identifier = row[geo_header["id"]]
        parent_of[identifier] = row[geo_header["pid"]]
        name = row[geo_header["name"]]
        parts = row[geo_header["ext_path"]].split()
        if name == PLACEHOLDER_REGION or not parts:
            continue
        if len(parts) > 1 and parts[-1] == parts[-2]:
            continue
        if row[geo_header["deep"]] not in RANK_BY_DEPTH:
            continue
        try:
            longitude, latitude = (float(part) for part in row[geo_header["geo"]].split()[:2])
        except ValueError:
            continue
        short, full, pinyin = names.get(identifier, (name, name, ""))
        places.append(
            {
                "id": identifier,
                # The official name ("黄浦区", "浦东新区"): it is what people type, and it says
                # which kind of unit it is. The short form is only used to qualify a district with
                # its city, where "浦东新区 · 上海市" would be needlessly long.
                "name": name,
                "short": short,
                "ascii": latin(short, full, pinyin),
                "country": HOME_COUNTRY,
                "lat": round(latitude, 3),
                "lon": round(longitude, 3),
                "rank": RANK_BY_DEPTH[row[geo_header["deep"]]],
                "population": 0,
            }
        )
    return places, parent_of


def load_world(path):
    """Cities abroad of 100k+ people, plus Hong Kong, Macao and Taiwan."""
    places = []
    home_regions = {}
    with open(path, encoding="utf-8") as handle:
        rows = [line.split("\t") for line in handle.read().splitlines()]
    for row in rows:
        if len(row) <= COL_POPULATION or row[COL_COUNTRY] == HOME_COUNTRY:
            continue
        population = int(row[COL_POPULATION] or 0)
        if row[COL_COUNTRY] in CHINESE_REGIONS:
            name = chinese_name(row)
            if name is None:
                continue
            place = {
                "id": None,
                "name": name,
                "short": name,
                "ascii": row[COL_ASCII],
                "country": HOME_COUNTRY,
                "lat": round(float(row[COL_LAT]), 3),
                "lon": round(float(row[COL_LON]), 3),
                # The finest unit available there, exactly like a district on the mainland.
                "rank": RANK_DISTRICT,
                "population": population,
            }
            # One place per name: the source carries several rows for the same built-up area
            # ("Yuen Long", "Yuen Long San Hui", "Yuen Long Kau Hui" — all 元朗).
            if population >= home_regions.get(name, {}).get("population", 0):
                home_regions[name] = place
            continue
        if population < MIN_FOREIGN_POPULATION:
            continue
        name = row[COL_NAME]
        ascii_name = row[COL_ASCII]
        places.append(
            {
                "id": None,
                "name": name,
                "short": name,
                "ascii": "" if ascii_name == name else ascii_name,
                "country": row[COL_COUNTRY],
                "lat": round(float(row[COL_LAT]), 3),
                "lon": round(float(row[COL_LON]), 3),
                # Never rank 1: that is reserved for the finest unit bundled (a Chinese district),
                # which is the only thing fine enough to name a position more precisely than its
                # city. Ranking GeoNames suburbs the same way would turn "Paris" into whichever
                # commune sits closest.
                "rank": RANK_REGION if row[COL_FEATURE_CODE] == "PPLC" else RANK_CITY,
                "population": population,
            }
        )
    return places + list(home_regions.values())


def resolve_parents(places, parent_of):
    """Turn each district's administrative parent into an index into the finished list.

    Only districts get one. A district says too little on its own — there are five 东区 in China —
    while a city is already unambiguous, and qualifying it would put "Guangzhou, Guangdong" into
    every prompt.

    The immediate parent row is often the one that was dropped as a duplicate (the 北京市 under
    北京市), so the chain is walked up until it reaches a row that survived, which for a
    municipality is the province entry itself.
    """
    index_of = {place["id"]: index for index, place in enumerate(places) if place["id"]}
    for place in places:
        place["parent"] = -1
        place["parentName"] = ""
        if place["rank"] != RANK_DISTRICT:
            continue
        current = parent_of.get(place["id"])
        while current and current not in index_of:
            current = parent_of.get(current)
        if current:
            place["parent"] = index_of[current]
            place["parentName"] = places[place["parent"]]["short"]


def build(level3_path, geo_path, geonames_path):
    china, parent_of = load_china(geo_path, level3_path)
    places = china + load_world(geonames_path)
    # Home first, then the most prominent, then by size. The picker relies on this order to offer
    # a sensible list before the user types anything: Chinese provinces and cities fill its first
    # screen, and the 2.8k districts are reachable by search.
    places.sort(
        key=lambda place: (
            place["country"] != HOME_COUNTRY,
            -place["rank"],
            -place["population"],
            place["ascii"] or place["name"],
        )
    )
    resolve_parents(places, parent_of)
    return places


def main():
    if len(sys.argv) != 4:
        sys.exit(__doc__)
    geonames_path, geo_path, level3_path = sys.argv[1:4]
    places = build(level3_path, geo_path, geonames_path)

    payload = {
        "v": 2,
        "name": [place["name"] for place in places],
        "ascii": [place["ascii"] for place in places],
        "country": [place["country"] for place in places],
        "lat": [place["lat"] for place in places],
        "lon": [place["lon"] for place in places],
        "rank": [place["rank"] for place in places],
        "parent": [place["parent"] for place in places],
        "parentName": [place["parentName"] for place in places],
    }
    text = json.dumps(payload, ensure_ascii=False, separators=(",", ":"))

    os.makedirs(os.path.dirname(OUT_PATH), exist_ok=True)
    with open(OUT_PATH, "w", encoding="utf-8", newline="\n") as handle:
        handle.write(text)
        handle.write("\n")

    counts = {}
    for place in places:
        counts[place["rank"]] = counts.get(place["rank"], 0) + 1
    home = sum(1 for place in places if place["country"] == HOME_COUNTRY)
    finest = [place for place in places if place["rank"] == RANK_DISTRICT]
    print(f"places written   : {len(places)} ({home} {HOME_COUNTRY} + {len(places) - home} world)")
    print(f"per rank         : {dict(sorted(counts.items(), reverse=True))}")
    print(f"finest units     : {len(finest)}")
    # The only ones without a city above them are the Hong Kong / Macao / Taiwan entries, which
    # have no mainland parent to point at.
    print(f"  with a parent  : {sum(1 for place in finest if place['parent'] >= 0)}")
    print(f"with a latin name: {sum(1 for place in places if place['ascii'])}")
    print(f"asset            : {OUT_PATH}  {os.path.getsize(OUT_PATH) / 1024:.1f} KB")


if __name__ == "__main__":
    main()
