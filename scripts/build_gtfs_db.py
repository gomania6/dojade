#!/usr/bin/env python3
"""
Ночной сборщик готовой SQLite-базы расписаний ZTM Poznań для OffDojade.

Что делает:
1. Скачивает свежий GTFS ZIP с сервера ZTM Poznań (https://www.ztm.poznan.pl/pl/dla-deweloperow/getGTFSFile).
2. Отфильтровывает пустые/битые строки и неиспользуемые данные.
3. Создает SQLite-базу `poznan_schedule.db` с 100% совместимой схемой Room (включая индексы и room_master_table).
4. Генерирует пешие пересадки (< 300 м, 1.2 м/с) по формуле Хаверсина с пространственной сеткой.
5. Оптимизирует базу (ANALYZE + VACUUM), сжимает в `poznan_schedule.db.gz` и создает `version.json`.
"""

from __future__ import annotations

import argparse
import csv
import datetime as dt
import gzip
import hashlib
import io
import json
import math
import os
import shutil
import sqlite3
import tempfile
import urllib.request
import zipfile
from collections import defaultdict
from pathlib import Path

ZTM_GTFS_URL = "https://www.ztm.poznan.pl/pl/dla-deweloperow/getGTFSFile"
ROOM_DB_VERSION = 1
ROOM_IDENTITY_HASH = "26172cec1c00d49b87e1ddbeb00c4118"

FOOTPATH_MAX_METERS = 300.0
WALKING_SPEED_MPS = 1.2
EARTH_RADIUS_METERS = 6_371_000.0
CELL_LAT_DEG = 0.003
CELL_LON_DEG = 0.005


def parse_gtfs_time_sec(raw: str | None) -> int | None:
    if not raw:
        return None
    parts = raw.strip().split(":")
    if len(parts) != 3:
        return None
    try:
        h, m, s = int(parts[0]), int(parts[1]), int(parts[2])
        return h * 3600 + m * 60 + s
    except ValueError:
        return None


def haversine_meters(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    d_lat = math.radians(lat2 - lat1)
    d_lon = math.radians(lon2 - lon1)
    r_lat1 = math.radians(lat1)
    r_lat2 = math.radians(lat2)
    a = math.sin(d_lat / 2.0) ** 2 + math.cos(r_lat1) * math.cos(r_lat2) * math.sin(d_lon / 2.0) ** 2
    return EARTH_RADIUS_METERS * 2.0 * math.atan2(math.sqrt(a), math.sqrt(1.0 - a))


def init_room_schema(conn: sqlite3.Connection) -> None:
    cur = conn.cursor()
    cur.executescript(
        f"""
        PRAGMA journal_mode = OFF;
        PRAGMA synchronous = OFF;
        PRAGMA temp_store = MEMORY;
        PRAGMA user_version = {ROOM_DB_VERSION};

        CREATE TABLE IF NOT EXISTS `agencies` (
            `agency_id` TEXT NOT NULL,
            `agency_name` TEXT NOT NULL,
            `agency_url` TEXT NOT NULL,
            `agency_timezone` TEXT NOT NULL,
            PRIMARY KEY(`agency_id`)
        );

        CREATE TABLE IF NOT EXISTS `routes` (
            `route_id` TEXT NOT NULL,
            `agency_id` TEXT NOT NULL,
            `short_name` TEXT NOT NULL,
            `long_name` TEXT NOT NULL,
            `route_type` INTEGER NOT NULL,
            `route_color` TEXT,
            `route_text_color` TEXT,
            PRIMARY KEY(`route_id`)
        );

        CREATE TABLE IF NOT EXISTS `stops` (
            `stop_id` TEXT NOT NULL,
            `stop_code` TEXT NOT NULL,
            `stop_name` TEXT NOT NULL,
            `stop_lat` REAL NOT NULL,
            `stop_lon` REAL NOT NULL,
            `zone_id` TEXT NOT NULL,
            `platform_code` TEXT NOT NULL,
            PRIMARY KEY(`stop_id`)
        );

        CREATE TABLE IF NOT EXISTS `calendars` (
            `service_id` TEXT NOT NULL,
            `monday` INTEGER NOT NULL,
            `tuesday` INTEGER NOT NULL,
            `wednesday` INTEGER NOT NULL,
            `thursday` INTEGER NOT NULL,
            `friday` INTEGER NOT NULL,
            `saturday` INTEGER NOT NULL,
            `sunday` INTEGER NOT NULL,
            `start_date` INTEGER NOT NULL,
            `end_date` INTEGER NOT NULL,
            PRIMARY KEY(`service_id`)
        );

        CREATE TABLE IF NOT EXISTS `calendar_dates` (
            `service_id` TEXT NOT NULL,
            `date` INTEGER NOT NULL,
            `exception_type` INTEGER NOT NULL,
            PRIMARY KEY(`service_id`, `date`)
        );

        CREATE TABLE IF NOT EXISTS `trips` (
            `trip_id` TEXT NOT NULL,
            `route_id` TEXT NOT NULL,
            `service_id` TEXT NOT NULL,
            `trip_headsign` TEXT NOT NULL,
            `direction_id` INTEGER NOT NULL,
            `shape_id` TEXT NOT NULL,
            PRIMARY KEY(`trip_id`)
        );

        CREATE TABLE IF NOT EXISTS `stop_times` (
            `trip_id` TEXT NOT NULL,
            `stop_sequence` INTEGER NOT NULL,
            `stop_id` TEXT NOT NULL,
            `arrival_time_sec` INTEGER NOT NULL,
            `departure_time_sec` INTEGER NOT NULL,
            PRIMARY KEY(`trip_id`, `stop_sequence`)
        );

        CREATE TABLE IF NOT EXISTS `shapes` (
            `shape_id` TEXT NOT NULL,
            `sequence` INTEGER NOT NULL,
            `lat` REAL NOT NULL,
            `lon` REAL NOT NULL,
            PRIMARY KEY(`shape_id`, `sequence`)
        );

        CREATE TABLE IF NOT EXISTS `transfers` (
            `from_stop_id` TEXT NOT NULL,
            `to_stop_id` TEXT NOT NULL,
            `transfer_type` INTEGER NOT NULL,
            `min_transfer_time_sec` INTEGER NOT NULL,
            `distance_meters` INTEGER NOT NULL,
            PRIMARY KEY(`from_stop_id`, `to_stop_id`)
        );

        CREATE TABLE IF NOT EXISTS room_master_table (
            id INTEGER PRIMARY KEY,
            identity_hash TEXT
        );
        INSERT OR REPLACE INTO room_master_table (id, identity_hash)
        VALUES (42, '{ROOM_IDENTITY_HASH}');
        """
    )


def create_room_indexes(conn: sqlite3.Connection) -> None:
    conn.executescript(
        """
        CREATE INDEX IF NOT EXISTS `index_routes_short_name` ON `routes` (`short_name`);
        CREATE INDEX IF NOT EXISTS `index_stops_stop_name` ON `stops` (`stop_name`);
        CREATE INDEX IF NOT EXISTS `index_stops_stop_lat_stop_lon` ON `stops` (`stop_lat`, `stop_lon`);
        CREATE INDEX IF NOT EXISTS `index_calendar_dates_date` ON `calendar_dates` (`date`);
        CREATE INDEX IF NOT EXISTS `index_calendar_dates_service_id` ON `calendar_dates` (`service_id`);
        CREATE INDEX IF NOT EXISTS `index_trips_service_id` ON `trips` (`service_id`);
        CREATE INDEX IF NOT EXISTS `index_trips_route_id` ON `trips` (`route_id`);
        CREATE INDEX IF NOT EXISTS `index_trips_shape_id` ON `trips` (`shape_id`);
        CREATE INDEX IF NOT EXISTS `index_stop_times_stop_id` ON `stop_times` (`stop_id`);
        CREATE INDEX IF NOT EXISTS `index_stop_times_trip_id` ON `stop_times` (`trip_id`);
        CREATE INDEX IF NOT EXISTS `index_shapes_shape_id` ON `shapes` (`shape_id`);
        CREATE INDEX IF NOT EXISTS `index_transfers_from_stop_id` ON `transfers` (`from_stop_id`);
        """
    )


def read_zip_csv(zf: zipfile.ZipFile, target_name: str):
    match = next((n for n in zf.namelist() if n.endswith("/" + target_name) or n == target_name), None)
    if not match:
        return
    with zf.open(match, "r") as raw:
        text = io.TextIOWrapper(raw, encoding="utf-8-sig", newline="")
        reader = csv.DictReader(text)
        for row in reader:
            yield {k.strip(): (v.strip() if v is not None else "") for k, v in row.items() if k}


def download_gtfs_zip(url: str, dest: Path) -> None:
    req = urllib.request.Request(
        url,
        headers={
            "Accept": "application/octet-stream",
            "User-Agent": "OffDojade-ScheduleBuilder/1.0",
        },
    )
    with urllib.request.urlopen(req, timeout=120) as resp, dest.open("wb") as out:
        shutil.copyfileobj(resp, out, length=64 * 1024)


def generate_footpaths(stops: list[tuple[str, float, float]], existing: set[tuple[str, str]]) -> list[tuple[str, str, int, int, int]]:
    grid: dict[tuple[int, int], list[tuple[str, float, float]]] = defaultdict(list)
    for stop_id, lat, lon in stops:
        grid[(math.floor(lat / CELL_LAT_DEG), math.floor(lon / CELL_LON_DEG))].append((stop_id, lat, lon))

    generated: list[tuple[str, str, int, int, int]] = []
    for s_id, s_lat, s_lon in stops:
        c_lat = math.floor(s_lat / CELL_LAT_DEG)
        c_lon = math.floor(s_lon / CELL_LON_DEG)
        for d_lat in (-1, 0, 1):
            for d_lon in (-1, 0, 1):
                for t_id, t_lat, t_lon in grid.get((c_lat + d_lat, c_lon + d_lon), ()):
                    if s_id == t_id or (s_id, t_id) in existing:
                        continue
                    dist = haversine_meters(s_lat, s_lon, t_lat, t_lon)
                    if dist <= FOOTPATH_MAX_METERS:
                        walk_sec = max(15, math.ceil(dist / WALKING_SPEED_MPS))
                        generated.append((s_id, t_id, 2, walk_sec, round(dist)))
    return generated


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(65536), b""):
            h.update(chunk)
    return h.hexdigest()


def build_database(zip_path: Path, output_dir: Path) -> dict:
    output_dir.mkdir(parents=True, exist_ok=True)
    db_path = output_dir / "poznan_schedule.db"
    gz_path = output_dir / "poznan_schedule.db.gz"
    if db_path.exists():
        db_path.unlink()

    conn = sqlite3.connect(str(db_path))
    init_room_schema(conn)
    cur = conn.cursor()

    stops_coords: list[tuple[str, float, float]] = []
    existing_transfers: set[tuple[str, str]] = set()
    valid_trip_ids: set[str] = set()
    valid_stop_ids: set[str] = set()

    with zipfile.ZipFile(zip_path, "r") as zf:
        agencies = []
        for r in read_zip_csv(zf, "agency.txt"):
            name = r.get("agency_name", "")
            if not name:
                continue
            agencies.append((
                r.get("agency_id") or "ztm_poznan",
                name,
                r.get("agency_url", ""),
                r.get("agency_timezone") or "Europe/Warsaw",
            ))
        cur.executemany("INSERT OR REPLACE INTO agencies VALUES (?, ?, ?, ?)", agencies)

        routes = []
        for r in read_zip_csv(zf, "routes.txt"):
            rid = r.get("route_id", "")
            if not rid:
                continue
            routes.append((
                rid,
                r.get("agency_id", ""),
                r.get("route_short_name") or rid,
                r.get("route_long_name", ""),
                int(r.get("route_type") or 3),
                r.get("route_color") or None,
                r.get("route_text_color") or None,
            ))
        cur.executemany("INSERT OR REPLACE INTO routes VALUES (?, ?, ?, ?, ?, ?, ?)", routes)

        stops = []
        for r in read_zip_csv(zf, "stops.txt"):
            sid = r.get("stop_id", "")
            if not sid:
                continue
            try:
                lat = float(r.get("stop_lat", ""))
                lon = float(r.get("stop_lon", ""))
            except ValueError:
                continue
            valid_stop_ids.add(sid)
            stops_coords.append((sid, lat, lon))
            stops.append((
                sid,
                r.get("stop_code", ""),
                r.get("stop_name") or sid,
                lat,
                lon,
                r.get("zone_id") or "A",
                r.get("platform_code", ""),
            ))
        cur.executemany("INSERT OR REPLACE INTO stops VALUES (?, ?, ?, ?, ?, ?, ?)", stops)

        calendars = []
        for r in read_zip_csv(zf, "calendar.txt"):
            sid = r.get("service_id", "")
            if not sid:
                continue
            try:
                start_d = int(r["start_date"])
                end_d = int(r["end_date"])
            except (KeyError, ValueError):
                continue
            calendars.append((
                sid,
                1 if r.get("monday") == "1" else 0,
                1 if r.get("tuesday") == "1" else 0,
                1 if r.get("wednesday") == "1" else 0,
                1 if r.get("thursday") == "1" else 0,
                1 if r.get("friday") == "1" else 0,
                1 if r.get("saturday") == "1" else 0,
                1 if r.get("sunday") == "1" else 0,
                start_d,
                end_d,
            ))
        cur.executemany("INSERT OR REPLACE INTO calendars VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", calendars)

        cal_dates = []
        for r in read_zip_csv(zf, "calendar_dates.txt"):
            sid = r.get("service_id", "")
            if not sid:
                continue
            try:
                cal_dates.append((sid, int(r["date"]), int(r["exception_type"])))
            except (KeyError, ValueError):
                continue
        cur.executemany("INSERT OR REPLACE INTO calendar_dates VALUES (?, ?, ?)", cal_dates)

        trips = []
        for r in read_zip_csv(zf, "trips.txt"):
            tid = r.get("trip_id", "")
            rid = r.get("route_id", "")
            sid = r.get("service_id", "")
            if not (tid and rid and sid):
                continue
            valid_trip_ids.add(tid)
            trips.append((
                tid,
                rid,
                sid,
                r.get("trip_headsign", ""),
                int(r.get("direction_id") or 0),
                r.get("shape_id", ""),
            ))
        cur.executemany("INSERT OR REPLACE INTO trips VALUES (?, ?, ?, ?, ?, ?)", trips)

        batch = []
        for r in read_zip_csv(zf, "stop_times.txt"):
            tid = r.get("trip_id", "")
            sid = r.get("stop_id", "")
            if tid not in valid_trip_ids or sid not in valid_stop_ids:
                continue
            try:
                seq = int(r["stop_sequence"])
            except (KeyError, ValueError):
                continue
            arr = parse_gtfs_time_sec(r.get("arrival_time"))
            dep = parse_gtfs_time_sec(r.get("departure_time"))
            arr_sec = arr if arr is not None else dep
            if arr_sec is None:
                continue
            dep_sec = dep if dep is not None else arr_sec
            batch.append((tid, seq, sid, arr_sec, dep_sec))
            if len(batch) >= 10_000:
                cur.executemany("INSERT OR REPLACE INTO stop_times VALUES (?, ?, ?, ?, ?)", batch)
                batch.clear()
        if batch:
            cur.executemany("INSERT OR REPLACE INTO stop_times VALUES (?, ?, ?, ?, ?)", batch)

        shape_batch = []
        for r in read_zip_csv(zf, "shapes.txt"):
            shid = r.get("shape_id", "")
            if not shid:
                continue
            try:
                shape_batch.append((shid, int(r["shape_pt_sequence"]), float(r["shape_pt_lat"]), float(r["shape_pt_lon"])))
            except (KeyError, ValueError):
                continue
            if len(shape_batch) >= 10_000:
                cur.executemany("INSERT OR REPLACE INTO shapes VALUES (?, ?, ?, ?)", shape_batch)
                shape_batch.clear()
        if shape_batch:
            cur.executemany("INSERT OR REPLACE INTO shapes VALUES (?, ?, ?, ?)", shape_batch)

        transfers = []
        for r in read_zip_csv(zf, "transfers.txt"):
            f_id = r.get("from_stop_id", "")
            t_id = r.get("to_stop_id", "")
            if f_id not in valid_stop_ids or t_id not in valid_stop_ids or f_id == t_id:
                continue
            min_time = int(r.get("min_transfer_time") or 120)
            existing_transfers.add((f_id, t_id))
            transfers.append((f_id, t_id, int(r.get("transfer_type") or 0), min_time, int(min_time * WALKING_SPEED_MPS)))
        cur.executemany("INSERT OR REPLACE INTO transfers VALUES (?, ?, ?, ?, ?)", transfers)

    footpaths = generate_footpaths(stops_coords, existing_transfers)
    cur.executemany("INSERT OR IGNORE INTO transfers VALUES (?, ?, ?, ?, ?)", footpaths)

    create_room_indexes(conn)
    conn.commit()
    conn.execute("ANALYZE;")
    conn.execute("VACUUM;")
    conn.close()

    db_sha256 = sha256_file(db_path)
    db_size = db_path.stat().st_size

    with db_path.open("rb") as src, gzip.open(gz_path, "wb", compresslevel=9) as dst:
        shutil.copyfileobj(src, dst, length=64 * 1024)

    gz_size = gz_path.stat().st_size
    metadata = {
        "build_date": dt.datetime.now(dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "sha256": db_sha256,
        "db_filename": "poznan_schedule.db.gz",
        "size_bytes": db_size,
        "compressed_size_bytes": gz_size,
        "stops_count": len(stops_coords),
        "trips_count": len(valid_trip_ids),
        "room_version": ROOM_DB_VERSION,
    }
    (output_dir / "version.json").write_text(json.dumps(metadata, indent=2) + "\n", encoding="utf-8")
    return metadata


def main() -> None:
    parser = argparse.ArgumentParser(description="Build prebuilt Room SQLite DB for ZTM Poznań")
    parser.add_argument("--zip", type=Path, default=None, help="Optional local GTFS ZIP path")
    parser.add_argument("--output-dir", type=Path, default=Path("dist"), help="Output directory")
    args = parser.parse_args()

    with tempfile.TemporaryDirectory() as tmp:
        zip_path = args.zip
        if zip_path is None:
            zip_path = Path(tmp) / "ztm_poznan_gtfs.zip"
            print(f"Downloading ZTM Poznań GTFS from {ZTM_GTFS_URL}...")
            download_gtfs_zip(ZTM_GTFS_URL, zip_path)
        meta = build_database(zip_path, args.output_dir)
        print("Build complete:", json.dumps(meta, indent=2))


if __name__ == "__main__":
    main()
