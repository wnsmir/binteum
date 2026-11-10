#!/usr/bin/env python3
"""Build a private-cache POI index from the official South Korea OSM extract.

Usage: python3 scripts/prepare-nearby-pois.py [--refresh]
Dependencies, the original extract, and the generated index stay in .local/maps/.
No device location, OSM contributor identity, or account credential is collected.
"""

import argparse
from collections import Counter
from datetime import datetime, timezone
import hashlib
import json
import math
import os
from pathlib import Path
import re
import subprocess
import sys
import time
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parent.parent
CACHE = ROOT / ".local" / "maps"
SOURCE = "https://download.geofabrik.de/asia/south-korea-latest.osm.pbf"
MAX_BYTES = 512 * 1024 * 1024


def ensure_osmium():
    """Never install into the user's global Python environment."""
    python = CACHE / "venv" / "bin" / "python"
    if Path(sys.prefix).resolve() != python.parent.parent.resolve():
        if not python.exists():
            subprocess.run([sys.executable, "-m", "venv", str(python.parent.parent)], check=True)
        result = subprocess.run([str(python), "-c", "import osmium"], capture_output=True)
        if result.returncode:
            subprocess.run([str(python), "-m", "pip", "install", "osmium==4.2.0"], check=True)
        os.execv(str(python), [str(python), str(Path(__file__).resolve()), *sys.argv[1:]])
    import osmium
    return osmium


def download(destination):
    """Resume an interrupted download only when the server honors its Range."""
    partial = destination.with_suffix(destination.suffix + ".part")
    offset = partial.stat().st_size if partial.exists() else 0
    if offset >= MAX_BYTES:
        raise RuntimeError("Partial download exceeds the 512 MiB limit; remove it before retrying.")
    headers = {"User-Agent": "BinteumPleosPrototype/0.1 (local POI preparation)"}
    with urlopen(Request(SOURCE + ".md5", headers=headers), timeout=30) as checksum_response:
        checksum_text = checksum_response.read(256).decode("ascii").strip()
    expected_md5 = checksum_text.split()[0] if checksum_text else ""
    if not re.fullmatch(r"[0-9a-fA-F]{32}", expected_md5):
        raise RuntimeError("The official source checksum could not be read.")
    if offset:
        previous_checksum = hashlib.md5()
        with partial.open("rb") as previous:
            for chunk in iter(lambda: previous.read(1024 * 1024), b""):
                previous_checksum.update(chunk)
        if previous_checksum.hexdigest() == expected_md5.lower():
            partial.replace(destination)
            return
        headers["Range"] = "bytes=%d-" % offset
    start = time.monotonic()
    with urlopen(Request(SOURCE, headers=headers), timeout=60) as response:
        resumed = response.status == 206 and offset > 0
        if resumed and not response.headers.get("Content-Range", "").startswith("bytes %d-" % offset):
            raise RuntimeError("Download server returned an unexpected byte range.")
        size = offset if resumed else 0
        expected = int(response.headers.get("Content-Length", "0"))
        if size + expected > MAX_BYTES:
            raise RuntimeError("Extract exceeds the 512 MiB download limit.")
        received = 0
        last_report = start
        with partial.open("ab" if resumed else "wb") as output:
            while True:
                chunk = response.read(1024 * 1024)
                if not chunk:
                    break
                size += len(chunk)
                received += len(chunk)
                if size > MAX_BYTES or time.monotonic() - start > 1800:
                    raise RuntimeError("Download exceeded its size or 30-minute time limit.")
                output.write(chunk)
                if time.monotonic() - last_report > 15:
                    print("Downloaded %.1f MiB" % (size / 1024 ** 2), flush=True)
                    last_report = time.monotonic()
        if expected and received != expected:
            raise RuntimeError("Incomplete extract; retry to resume the partial download.")
    checksum = hashlib.md5()
    with partial.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            checksum.update(chunk)
    if checksum.hexdigest() != expected_md5.lower():
        partial.unlink()
        raise RuntimeError("Official checksum mismatch (the latest extract may have changed); retry a fresh download.")
    partial.replace(destination)


def category(tags):
    amenity = tags.get("amenity")
    if amenity == "cafe":
        result = "CAFE"
    elif amenity in ("restaurant", "fast_food"):
        result = "FOOD"
    elif tags.get("shop") == "convenience":
        result = "CONVENIENCE"
    elif tags.get("leisure") == "park":
        result = "PARK"
    else:
        return None
    if tags.get("access") in ("private", "no") or tags.get("foot") in ("private", "no"):
        return None
    if any(tags.get(key) == "yes" for key in ("disused", "abandoned", "demolished")):
        return None
    return result


def address(tags):
    if tags.get("addr:full"):
        return tags.get("addr:full")
    return " ".join(tags.get(key) for key in
                    ("addr:province", "addr:city", "addr:suburb", "addr:street", "addr:housenumber")
                    if tags.get(key))


def way_reference(points):
    """Use an interior center when possible, otherwise a point on the source way.

    This is a geometry reference only: it never claims to be a public entrance.
    The local projection is adequate for choosing a point, not for route timing.
    """
    mean_lat = sum(p[1] for p in points) / len(points)
    scale = math.cos(math.radians(mean_lat))
    projected = [(p[0] * scale, p[1]) for p in points]
    center = ((min(p[0] for p in projected) + max(p[0] for p in projected)) / 2,
              (min(p[1] for p in projected) + max(p[1] for p in projected)) / 2)
    closed = points[0] == points[-1] and len(points) >= 4
    inside = False
    if closed:
        for a, b in zip(projected, projected[1:]):
            if (a[1] > center[1]) != (b[1] > center[1]):
                crossing = a[0] + (center[1] - a[1]) * (b[0] - a[0]) / (b[1] - a[1])
                if center[0] < crossing:
                    inside = not inside
    if inside:
        return center[0] / scale, center[1]
    candidates = []
    for a, b in zip(projected, projected[1:]):
        dx, dy = b[0] - a[0], b[1] - a[1]
        length_sq = dx * dx + dy * dy
        fraction = max(0, min(1, ((center[0] - a[0]) * dx + (center[1] - a[1]) * dy) / length_sq)) if length_sq else 0
        point = (a[0] + fraction * dx, a[1] + fraction * dy)
        candidates.append(point)
    point = min(candidates, key=lambda p: (p[0] - center[0]) ** 2 + (p[1] - center[1]) ** 2)
    return point[0] / scale, point[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--refresh", action="store_true", help="download the current extract again")
    args = parser.parse_args()
    CACHE.mkdir(parents=True, exist_ok=True)
    osmium = ensure_osmium()
    source_file = CACHE / "south-korea-latest.osm.pbf"
    output_file = CACHE / "south-korea-pois.json"
    if args.refresh or not source_file.exists():
        download(source_file)
    with osmium.io.Reader(str(source_file)) as reader:
        header = reader.header()
        timestamp = header.get("osmosis_replication_timestamp")
        datetime.fromisoformat(timestamp.replace("Z", "+00:00"))
        box = header.box()
        if not box.valid():
            raise RuntimeError("The official extract has no valid geographic bounds.")
        bounds = {"south": box.bottom_left.lat, "north": box.top_right.lat,
                  "west": box.bottom_left.lon, "east": box.top_right.lon}

    places = []
    skipped_geometry = 0

    class Places(osmium.SimpleHandler):
        def add(self, item, kind, point):
            name = item.tags.get("name:ko") or item.tags.get("name")
            group = category(item.tags)
            if not name or not group:
                return
            lon, lat = point
            if not (bounds["south"] <= lat <= bounds["north"] and bounds["west"] <= lon <= bounds["east"]):
                return
            identity = "%s/%s" % (kind, item.id)
            places.append({"id": identity, "category": group, "name": name,
                           "address": address(item.tags), "latitude": round(lat, 7), "longitude": round(lon, 7),
                           "url": "https://www.openstreetmap.org/" + identity,
                           "positionType": "node" if kind == "node" else "representative_point"})

        def node(self, node):
            if (node.tags.get("name:ko") or node.tags.get("name")) and category(node.tags) and node.location.valid():
                self.add(node, "node", (node.location.lon, node.location.lat))

        def way(self, way):
            nonlocal skipped_geometry
            if not category(way.tags) or not (way.tags.get("name:ko") or way.tags.get("name")):
                return
            if len(way.nodes) < 2 or any(not node.location.valid() for node in way.nodes):
                skipped_geometry += 1
                return
            self.add(way, "way", way_reference([(node.location.lon, node.location.lat) for node in way.nodes]))

    locations = CACHE / "node-locations.cache"
    locations.unlink(missing_ok=True)
    print("Extracting named public cafes, food, convenience stores and parks...", flush=True)
    try:
        Places().apply_file(str(source_file), locations=True, idx="sparse_file_array," + str(locations))
    finally:
        locations.unlink(missing_ok=True)
    if not places:
        raise RuntimeError("No POIs extracted; existing JSON was not changed.")
    digest = hashlib.sha256()
    with source_file.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    data = {"formatVersion": 1, "source": "Geofabrik / OpenStreetMap", "sourceUrl": SOURCE,
            "datasetTimestamp": timestamp, "sourceSha256": digest.hexdigest(),
            "generatedAt": datetime.now(timezone.utc).isoformat(),
            "attribution": "© OpenStreetMap contributors · Geofabrik",
            "license": "ODbL 1.0", "licenseUrl": "https://www.openstreetmap.org/copyright",
            "coverage": {"countryCode": "KR", "bounds": bounds},
            "limitations": ["Named nodes and ways only; relations are not extracted.",
                            "OSM coverage and opening hours are not guaranteed current or complete.",
                            "Way coordinates are geometric reference points, not verified entrances.",
                            "No route durations or current device locations are part of this index."],
            "counts": dict(Counter(place["category"] for place in places)),
            "skippedWaysWithMissingGeometry": skipped_geometry, "places": places}
    temporary = output_file.with_suffix(".json.tmp")
    with temporary.open("w", encoding="utf-8") as output:
        json.dump(data, output, ensure_ascii=False, separators=(",", ":"))
        output.write("\n")
    temporary.replace(output_file)
    print(json.dumps({"output": str(output_file), "datasetTimestamp": timestamp,
                      "counts": data["counts"], "total": len(places),
                      "skippedWaysWithMissingGeometry": skipped_geometry}, ensure_ascii=False), flush=True)


if __name__ == "__main__":
    main()
