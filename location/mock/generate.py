#!/usr/bin/env python3
"""Regenerate MOCK_RESPONSE_JSON in GeofenceApiService.kt from regions.json.

Edit location/mock/regions.json with the geofences you want, then run:
    python3 location/mock/generate.py

Required per region: lat, lng. Optional with defaults:
    id (auto 1..N), name ("Region N"), radius (150), external_id ("test-N"),
    transition_types (["enter", "exit"]).

Optional top-level "config" block is passed through verbatim — omit it to
exercise the SDK's built-in fallbacks (24h freshness, 1h cooldown, 1km local
refresh trigger, 5km remote fetch trigger, 19 max business geofences).
"""
import json
import re
import sys
import unicodedata
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
INPUT_FILE = Path(__file__).resolve().parent / "regions.json"
KOTLIN_FILE = (
    REPO_ROOT
    / "location/src/main/kotlin/io/customer/location/geofence/api/GeofenceApiService.kt"
)

BEGIN_MARKER = "// === BEGIN GENERATED MOCK ==="
END_MARKER = "// === END GENERATED MOCK ==="


def slugify(text):
    """ASCII slug suitable for embedding in a geofence id — lowercase,
    alphanumerics joined by dashes, no leading/trailing dashes."""
    normalized = unicodedata.normalize("NFKD", text).encode("ascii", "ignore").decode("ascii")
    slug = re.sub(r"[^a-zA-Z0-9]+", "-", normalized).strip("-").lower()
    return slug or "region"


def build_region(index, raw):
    if "lat" not in raw or "lng" not in raw:
        raise ValueError(f"region #{index} missing required lat/lng: {raw}")
    name = raw.get("name", f"Region {index}")
    # Default id embeds the name so logs / notifications stay identifiable
    # ("ENTER id=2-shahkam-flyover" beats "ENTER id=2"). Numeric prefix keeps
    # ids unique if two regions share a name. Explicit `id` in input wins.
    default_id = f"{index}-{slugify(name)}"
    region = {
        "id": raw.get("id", default_id),
        "name": name,
        "latitude": raw["lat"],
        "longitude": raw["lng"],
        "radius": raw.get("radius", 150),
        "external_id": raw.get("external_id", f"test-{index}"),
    }
    if "transition_types" in raw:
        region["transition_types"] = raw["transition_types"]
    return region


def main():
    if not INPUT_FILE.exists():
        sys.exit(f"Missing input file: {INPUT_FILE}")
    if not KOTLIN_FILE.exists():
        sys.exit(f"Missing target Kotlin file: {KOTLIN_FILE}")

    with INPUT_FILE.open() as f:
        data = json.load(f)

    raw_regions = data.get("geofences") or []
    if not raw_regions:
        sys.exit("No geofences in regions.json — add at least one entry.")

    regions = [build_region(i + 1, r) for i, r in enumerate(raw_regions)]
    response = {"geofences": regions}
    # Pass-through: omit the config block when regions.json doesn't have one so
    # the SDK exercises its constants. Specify only the fields you want to
    # override in regions.json — partial blocks fall through cleanly because
    # every field in GeofenceApiConfig is nullable.
    if data.get("config"):
        response = {"config": data["config"], "geofences": regions}

    body = json.dumps(response, indent=2)
    # Match the existing 14-space indent inside the Kotlin raw string so
    # trimIndent() lines up the same way regardless of region count.
    indented_body = "\n".join(f"              {line}" for line in body.splitlines())

    block = (
        "        // === BEGIN GENERATED MOCK ===\n"
        "        private val MOCK_RESPONSE_JSON = \"\"\"\n"
        f"{indented_body}\n"
        "        \"\"\".trimIndent()\n"
        "        // === END GENERATED MOCK ==="
    )

    text = KOTLIN_FILE.read_text()
    pattern = re.compile(
        r" *" + re.escape(BEGIN_MARKER) + r".*?" + re.escape(END_MARKER),
        re.DOTALL,
    )
    new_text, count = pattern.subn(block, text)
    if count == 0:
        sys.exit(
            f"Could not find generated-mock markers in {KOTLIN_FILE.name}. "
            "Did someone delete them?"
        )

    KOTLIN_FILE.write_text(new_text)
    print(f"Wrote {len(regions)} geofence(s) into {KOTLIN_FILE.name}.")


if __name__ == "__main__":
    main()
