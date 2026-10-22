"""Import one plant from a GrowBuddy 0.1.6 database into a running v2 server.

Copies the plant (nickname, strain, autoflower, thumbnail) and its manual log
entries (milestones, feedings, adjustments, comments) through the public API.
0.1.6 ids are reused as UUIDs, so running it again changes nothing.

    uv run python scripts/import_v016.py growbuddy-0.1.6.db "My Plant" \
        --server http://growbuddy:8000 --token ... [--sensor-id 1]
"""

from __future__ import annotations

import argparse
import base64
import json
import sqlite3
import sys
import uuid
from datetime import UTC, datetime
from typing import Any

import httpx

# 0.1.6 log types that v2 knows (AutoLog/Undefined are hourly sensor rows, not journal entries).
TYPES = {
    "Germination", "Sprout", "Inspection", "LightAdjust", "HumidityAdjust",
    "TemperatureAdjust", "Feeding", "Topping", "LST", "Pruning", "FlowerFlip",
    "Harvest", "Trim", "Cure", "TerminatedEarly",
}  # fmt: skip


def ms(text: str) -> int:
    ts = datetime.fromisoformat(text)
    if ts.tzinfo is None:  # 0.1.6 wrote some milestone times without an offset; they are UTC
        ts = ts.replace(tzinfo=UTC)
    return int(ts.timestamp() * 1000)


def as_uuid(legacy_id: str) -> str:
    try:
        return str(uuid.UUID(legacy_id))
    except ValueError:
        return str(uuid.uuid5(uuid.NAMESPACE_URL, f"growbuddy-0.1.6:{legacy_id}"))


def log_data(log_type: str, data: dict[str, Any]) -> dict[str, Any]:
    if log_type == "Feeding":
        out: dict[str, Any] = {
            "nutrients": [
                {
                    "brand": n["brand"],
                    "product": n["nutrition"]["name"],
                    "amount": n.get("value", 0),
                    "unit": n["nutrition"].get("unitString") or "ml/L",
                }
                for n in (data.get("nutrients") or {}).values()
            ]
        }
        for old, new in (("pH", "ph"), ("EC", "ec"), ("volume", "volume_l")):
            if data.get(old) is not None:
                out[new] = data[old]
        return out
    if log_type == "HumidityAdjust" and "newHumidityTarget" in data:
        return {"humidity_target_pct": data["newHumidityTarget"]}
    if log_type == "TemperatureAdjust" and "newTemperatureTarget" in data:
        return {"temperature_target_c": data["newTemperatureTarget"]}
    if log_type == "LightAdjust" and "newLightHeight" in data:
        return {"light_height_cm": data["newLightHeight"]}
    return {}


def ok(resp: httpx.Response) -> dict[str, Any]:
    if resp.status_code >= 400:
        sys.exit(f"{resp.request.method} {resp.request.url.path}: {resp.status_code} {resp.text}")
    return resp.json() if resp.content else {}


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawTextHelpFormatter)
    p.add_argument("db", help="copy of the 0.1.6 growbuddy.db")
    p.add_argument("nickname", help='plant to import, e.g. "My Plant"')
    p.add_argument("--server", required=True)
    p.add_argument("--token", required=True)
    p.add_argument("--sensor-id", type=int, help="v2 sensor the plant is at (optional)")
    args = p.parse_args()

    old = sqlite3.connect(f"file:{args.db}?mode=ro", uri=True)
    plants = [
        (pid, json.loads(raw))
        for pid, raw in old.execute("SELECT id, data_json FROM Plants")
        if json.loads(raw).get("NickName") == args.nickname
    ]
    if len(plants) != 1:
        sys.exit(f"expected one 0.1.6 plant named {args.nickname!r}, found {len(plants)}")
    legacy_id, plant = plants[0]
    plant_id = as_uuid(legacy_id)

    api = httpx.Client(
        base_url=args.server.rstrip("/") + "/api/v1",
        headers={"Authorization": f"Bearer {args.token}"},
        timeout=30,
    )

    strain = plant.get("Strain") or {}
    matches = ok(api.get("/strains", params={"q": strain.get("name", ""), "limit": 50}))["items"]
    strain_id = next(
        (s["id"] for s in matches if s["name"] == strain.get("name")),
        None,
    )
    print(f"strain {strain.get('name')!r} -> {strain_id or 'not in catalog, left empty'}")

    photo_id = None
    if plant.get("thumbnail"):
        photo_id = as_uuid(f"{legacy_id}/thumbnail")
        ok(
            api.post(
                "/photos",
                data={"id": photo_id},
                files={"file": ("thumb.jpg", base64.b64decode(plant["thumbnail"]), "image/jpeg")},
            )
        )

    body: dict[str, Any] = {
        "id": plant_id,
        "nickname": plant["NickName"],
        "strain_id": strain_id,
        "is_autoflower": strain.get("category") == "Autoflower",
        "sensor_id": args.sensor_id,
        "thumbnail_photo_id": photo_id,
    }
    created = api.post("/plants", json=body)
    state = "created" if created.status_code == 201 else "exists"
    print(f"plant {plant['NickName']} {plant_id}: {state}")
    ok(created)

    rows = old.execute(
        "SELECT id, timestamp, data_json FROM Logs"
        " WHERE plant_id = ? AND is_auto = 0 ORDER BY timestamp",
        (legacy_id,),
    ).fetchall()
    for log_id, ts, raw in rows:
        entry = json.loads(raw)
        log_type = entry.get("type")
        if log_type not in TYPES:
            print(f"  skip {ts} {log_type}")
            continue
        resp = api.post(
            "/logs",
            json={
                "id": as_uuid(log_id),
                "ts": ms(ts),
                "type": log_type,
                "plant_ids": [plant_id],
                "comment": entry.get("comments") or None,
                "photo_ids": [],
                "data": log_data(log_type, entry.get("data") or {}),
            },
        )
        ok(resp)
        print(f"  {'created' if resp.status_code == 201 else 'exists '} {ts[:16]} {log_type}")

    result = ok(api.get(f"/plants/{plant_id}"))
    print(f"v2 says: phase {result.get('phase')}, age {result.get('age')}")


if __name__ == "__main__":
    main()
