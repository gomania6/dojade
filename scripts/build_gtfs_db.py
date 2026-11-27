#!/usr/bin/env python3
"""
Ночной сборщик готовой SQLite-базы расписаний ZTM Poznań для PyrTransit.

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
POLISH_TRAINS_GTFS_URL = "https://mkuran.pl/gtfs/polish_trains.zip"
KW_FALLBACK_GTFS_URL = "http://ws.kolejewlkp.pl:83/gtfs_kw.zip"
RAIL_AGENCIES = {"KW", "PR"}
RAIL_ID_PREFIX = "rail_"

# Границы Познаньской агломерации (Зоны A–D + ближайшие пригороды PKM)
POZNAN_MIN_LAT = 52.15
POZNAN_MAX_LAT = 52.62
POZNAN_MIN_LON = 16.45
POZNAN_MAX_LON = 17.35
POZNAN_CENTER_LAT = 52.4082
POZNAN_CENTER_LON = 16.9125

ROOM_DB_VERSION = 3
ROOM_IDENTITY_HASH = "5580ec83493b75b81b4aae9882bdb229"

FOOTPATH_MAX_METERS = 300.0
RAIL_FOOTPATH_MAX_METERS = 450.0
WALKING_SPEED_MPS = 1.2
EARTH_RADIUS_METERS = 6_371_000.0
CELL_LAT_DEG = 0.0045
CELL_LON_DEG = 0.0075


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
    a = min(1.0, max(0.0, math.sin(d_lat / 2.0) ** 2 + math.cos(r_lat1) * math.cos(r_lat2) * math.sin(d_lon / 2.0) ** 2))
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
            `wheelchair_accessible` INTEGER NOT NULL DEFAULT 0,
            PRIMARY KEY(`trip_id`)
        );

        CREATE TABLE IF NOT EXISTS `stop_times` (
            `trip_id` TEXT NOT NULL,
            `stop_sequence` INTEGER NOT NULL,
            `stop_id` TEXT NOT NULL,
            `arrival_time_sec` INTEGER NOT NULL,
            `departure_time_sec` INTEGER NOT NULL,
            `is_on_demand` INTEGER NOT NULL DEFAULT 0,
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
            "User-Agent": "PyrTransit-ScheduleBuilder/1.0",
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
        s_is_rail = s_id.startswith(RAIL_ID_PREFIX)
        c_lat = math.floor(s_lat / CELL_LAT_DEG)
        c_lon = math.floor(s_lon / CELL_LON_DEG)
        for d_lat in (-1, 0, 1):
            for d_lon in (-1, 0, 1):
                for t_id, t_lat, t_lon in grid.get((c_lat + d_lat, c_lon + d_lon), ()):
                    if s_id == t_id or (s_id, t_id) in existing:
                        continue
                    max_dist = RAIL_FOOTPATH_MAX_METERS if (s_is_rail or t_id.startswith(RAIL_ID_PREFIX)) else FOOTPATH_MAX_METERS
                    dist = haversine_meters(s_lat, s_lon, t_lat, t_lon)
                    if dist <= max_dist:
                        walk_sec = max(15, math.ceil(dist / WALKING_SPEED_MPS))
                        generated.append((s_id, t_id, 2, walk_sec, round(dist)))
    return generated


def infer_rail_zone(stop_name: str, lat: float, lon: float) -> str:
    lower = stop_name.strip().lower()
    if lower.startswith("poznań") or lower.startswith("poznan") or lower == "kiekrz":
        return "A"
    dist_km = haversine_meters(POZNAN_CENTER_LAT, POZNAN_CENTER_LON, lat, lon) / 1000.0
    if dist_km <= 16.0:
        return "B"
    if dist_km <= 28.0:
        return "C"
    return "D"


def merge_rail_gtfs(
    cur: sqlite3.Cursor,
    rail_zip_path: Path,
    stops_coords: list[tuple[str, float, float]],
) -> tuple[int, int]:
    """
    Добавляет в SQLite-базу пригородные электрички Koleje Wielkopolskie (KW) и Polregio (PR)
    в пределах Познаньской агломерации (с префиксом `rail_`, без коллизий с ID ZTM).
    Возвращает `(rail_stops_count, rail_trips_count)`.
    """
    with zipfile.ZipFile(rail_zip_path, "r") as zf:
        all_agencies = {}
        for r in read_zip_csv(zf, "agency.txt"):
            aid = r.get("agency_id", "")
            if aid in RAIL_AGENCIES:
                all_agencies[aid] = r
        # Если это прямой фид только от Koleje Wielkopolskie (где agency_id может отличаться)
        if not all_agencies:
            for r in read_zip_csv(zf, "agency.txt"):
                aid = r.get("agency_id") or "KW"
                name = r.get("agency_name", "")
                if "wielkopolsk" in name.lower() or "polregio" in name.lower():
                    all_agencies[aid] = r
        if not all_agencies:
            return 0, 0

        raw_stops: dict[str, dict] = {}
        bbox_stop_ids: set[str] = set()
        for r in read_zip_csv(zf, "stops.txt"):
            sid = r.get("stop_id", "")
            if not sid:
                continue
            try:
                lat = float(r.get("stop_lat", ""))
                lon = float(r.get("stop_lon", ""))
            except ValueError:
                continue
            if POZNAN_MIN_LAT <= lat <= POZNAN_MAX_LAT and POZNAN_MIN_LON <= lon <= POZNAN_MAX_LON:
                raw_stops[sid] = r
                bbox_stop_ids.add(sid)

        if not bbox_stop_ids:
            return 0, 0

        allowed_routes: dict[str, dict] = {}
        for r in read_zip_csv(zf, "routes.txt"):
            rid = r.get("route_id", "")
            aid = r.get("agency_id", "") or next(iter(all_agencies))
            if not rid or aid not in all_agencies:
                continue
            rtype = int(r.get("route_type") or 2)
            # Включаем электрички (route_type=2 / 100..109) и при желании только ж/д линии
            if rtype not in (2, 100, 101, 102, 103, 106, 109):
                continue
            allowed_routes[rid] = r

        raw_trips: dict[str, dict] = {}
        for r in read_zip_csv(zf, "trips.txt"):
            tid = r.get("trip_id", "")
            rid = r.get("route_id", "")
            sid = r.get("service_id", "")
            if tid and sid and rid in allowed_routes:
                raw_trips[tid] = r

        trip_stop_times: dict[str, list[tuple[int, str, int, int]]] = defaultdict(list)
        for r in read_zip_csv(zf, "stop_times.txt"):
            tid = r.get("trip_id", "")
            sid = r.get("stop_id", "")
            if tid not in raw_trips or sid not in bbox_stop_ids:
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
            trip_stop_times[tid].append((seq, sid, arr_sec, dep_sec))

        valid_trip_ids = {tid for tid, sts in trip_stop_times.items() if len(sts) >= 2}
        if not valid_trip_ids:
            return 0, 0

        used_stop_ids: set[str] = set()
        used_route_ids: set[str] = set()
        used_service_ids: set[str] = set()
        used_shape_ids: set[str] = set()

        for tid in valid_trip_ids:
            t = raw_trips[tid]
            used_route_ids.add(t["route_id"])
            used_service_ids.add(t["service_id"])
            shid = t.get("shape_id", "")
            if shid:
                used_shape_ids.add(shid)
            for _, sid, _, _ in trip_stop_times[tid]:
                used_stop_ids.add(sid)

        agencies_rows = [
            (
                RAIL_ID_PREFIX + aid,
                a.get("agency_name", aid),
                a.get("agency_url", ""),
                a.get("agency_timezone") or "Europe/Warsaw",
            )
            for aid, a in all_agencies.items()
        ]
        cur.executemany("INSERT OR REPLACE INTO agencies VALUES (?, ?, ?, ?)", agencies_rows)

        routes_rows = []
        for rid in used_route_ids:
            r = allowed_routes[rid]
            aid = r.get("agency_id") or next(iter(all_agencies))
            short = (r.get("route_short_name") or rid).strip()
            routes_rows.append((
                RAIL_ID_PREFIX + rid,
                RAIL_ID_PREFIX + aid,
                short,
                r.get("route_long_name", ""),
                2,  # Нормализуем к стандартному GTFS route_type = 2 (Rail)
                r.get("route_color") or ("E6000A" if aid == "KW" else "1E4E8C"),
                r.get("route_text_color") or "FFFFFF",
            ))
        cur.executemany("INSERT OR REPLACE INTO routes VALUES (?, ?, ?, ?, ?, ?, ?)", routes_rows)

        stops_rows = []
        for sid in used_stop_ids:
            s = raw_stops[sid]
            lat = float(s["stop_lat"])
            lon = float(s["stop_lon"])
            name = (s.get("stop_name") or sid).strip()
            pref_sid = RAIL_ID_PREFIX + sid
            zone = infer_rail_zone(name, lat, lon)
            stops_coords.append((pref_sid, lat, lon))
            stops_rows.append((
                pref_sid,
                s.get("stop_code", ""),
                name,
                lat,
                lon,
                zone,
                s.get("platform_code", ""),
            ))
        cur.executemany("INSERT OR REPLACE INTO stops VALUES (?, ?, ?, ?, ?, ?, ?)", stops_rows)

        calendars_rows = []
        for r in read_zip_csv(zf, "calendar.txt"):
            sid = r.get("service_id", "")
            if sid not in used_service_ids:
                continue
            try:
                start_d = int(r["start_date"])
                end_d = int(r["end_date"])
            except (KeyError, ValueError):
                continue
            calendars_rows.append((
                RAIL_ID_PREFIX + sid,
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
        if calendars_rows:
            cur.executemany("INSERT OR REPLACE INTO calendars VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", calendars_rows)

        cal_dates_rows = []
        for r in read_zip_csv(zf, "calendar_dates.txt"):
            sid = r.get("service_id", "")
            if sid not in used_service_ids:
                continue
            try:
                cal_dates_rows.append((RAIL_ID_PREFIX + sid, int(r["date"]), int(r["exception_type"])))
            except (KeyError, ValueError):
                continue
        if cal_dates_rows:
            cur.executemany("INSERT OR REPLACE INTO calendar_dates VALUES (?, ?, ?)", cal_dates_rows)

        trips_rows = []
        st_rows = []
        for tid in valid_trip_ids:
            t = raw_trips[tid]
            sts = sorted(trip_stop_times[tid], key=lambda x: x[0])
            last_stop_name = (raw_stops[sts[-1][1]].get("stop_name") or "").strip()
            headsign = (t.get("trip_headsign") or last_stop_name).strip()
            shid = t.get("shape_id", "")
            trips_rows.append((
                RAIL_ID_PREFIX + tid,
                RAIL_ID_PREFIX + t["route_id"],
                RAIL_ID_PREFIX + t["service_id"],
                headsign,
                int(t.get("direction_id") or 0),
                (RAIL_ID_PREFIX + shid) if shid else "",
                int(t.get("wheelchair_accessible") or 1),
            ))
            for seq, sid, arr_sec, dep_sec in sts:
                st_rows.append((RAIL_ID_PREFIX + tid, seq, RAIL_ID_PREFIX + sid, arr_sec, dep_sec, 0))
        cur.executemany("INSERT OR REPLACE INTO trips VALUES (?, ?, ?, ?, ?, ?, ?)", trips_rows)
        for i in range(0, len(st_rows), 10_000):
            cur.executemany("INSERT OR REPLACE INTO stop_times VALUES (?, ?, ?, ?, ?, ?)", st_rows[i:i + 10_000])

        # Сохраняем только точки геометрии shapes внутри расширенного bbox Познани
        if used_shape_ids:
            shape_batch = []
            for r in read_zip_csv(zf, "shapes.txt"):
                shid = r.get("shape_id", "")
                if shid not in used_shape_ids:
                    continue
                try:
                    lat = float(r["shape_pt_lat"])
                    lon = float(r["shape_pt_lon"])
                    seq = int(r["shape_pt_sequence"])
                except (KeyError, ValueError):
                    continue
                if (POZNAN_MIN_LAT - 0.10) <= lat <= (POZNAN_MAX_LAT + 0.10) and (POZNAN_MIN_LON - 0.15) <= lon <= (POZNAN_MAX_LON + 0.15):
                    shape_batch.append((RAIL_ID_PREFIX + shid, seq, lat, lon))
                    if len(shape_batch) >= 10_000:
                        cur.executemany("INSERT OR REPLACE INTO shapes VALUES (?, ?, ?, ?)", shape_batch)
                        shape_batch.clear()
            if shape_batch:
                cur.executemany("INSERT OR REPLACE INTO shapes VALUES (?, ?, ?, ?)", shape_batch)

        return len(stops_rows), len(trips_rows)


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(65536), b""):
            h.update(chunk)
    return h.hexdigest()


def build_database(zip_path: Path, output_dir: Path, rail_zip_path: Path | None = None) -> dict:
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
                int(r.get("wheelchair_accessible") or 0),
            ))
        cur.executemany("INSERT OR REPLACE INTO trips VALUES (?, ?, ?, ?, ?, ?, ?)", trips)

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
            pickup_type = int(r.get("pickup_type") or 0)
            drop_off_type = int(r.get("drop_off_type") or 0)
            is_on_demand = 1 if (pickup_type == 3 or drop_off_type == 3) else 0
            batch.append((tid, seq, sid, arr_sec, dep_sec, is_on_demand))
            if len(batch) >= 10_000:
                cur.executemany("INSERT OR REPLACE INTO stop_times VALUES (?, ?, ?, ?, ?, ?)", batch)
                batch.clear()
        if batch:
            cur.executemany("INSERT OR REPLACE INTO stop_times VALUES (?, ?, ?, ?, ?, ?)", batch)

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

    rail_stops_count = 0
    rail_trips_count = 0
    if rail_zip_path is not None and rail_zip_path.exists():
        print(f"Merging regional rail GTFS (Koleje Wielkopolskie + Polregio) from {rail_zip_path}...")
        rail_stops_count, rail_trips_count = merge_rail_gtfs(cur, rail_zip_path, stops_coords)
        print(f"Merged rail data: {rail_stops_count} stops, {rail_trips_count} trips.")

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
        "trips_count": len(valid_trip_ids) + rail_trips_count,
        "rail_stops_count": rail_stops_count,
        "rail_trips_count": rail_trips_count,
        "room_version": ROOM_DB_VERSION,
    }
    (output_dir / "version.json").write_text(json.dumps(metadata, indent=2) + "\n", encoding="utf-8")
    return metadata


def download_rail_gtfs_with_fallback(dest: Path) -> Path | None:
    for label, url in (
        ("Polish Trains (KW + Polregio)", POLISH_TRAINS_GTFS_URL),
        ("Koleje Wielkopolskie Direct", KW_FALLBACK_GTFS_URL),
    ):
        try:
            print(f"Downloading {label} GTFS from {url}...")
            download_gtfs_zip(url, dest)
            return dest
        except Exception as exc:
            print(f"WARNING: Failed to download {label} GTFS: {exc}")
    return None


def main() -> None:
    parser = argparse.ArgumentParser(description="Build prebuilt Room SQLite DB for ZTM Poznań + KW/Polregio Rail")
    parser.add_argument("--zip", type=Path, default=None, help="Optional local ZTM GTFS ZIP path")
    parser.add_argument("--rail-zip", type=Path, default=None, help="Optional local Rail GTFS ZIP path")
    parser.add_argument("--no-rail", action="store_true", help="Skip Koleje Wielkopolskie / Polregio rail merge")
    parser.add_argument("--output-dir", type=Path, default=Path("dist"), help="Output directory")
    args = parser.parse_args()

    with tempfile.TemporaryDirectory() as tmp:
        zip_path = args.zip
        if zip_path is None:
            zip_path = Path(tmp) / "ztm_poznan_gtfs.zip"
            print(f"Downloading ZTM Poznań GTFS from {ZTM_GTFS_URL}...")
            download_gtfs_zip(ZTM_GTFS_URL, zip_path)

        rail_zip_path: Path | None = args.rail_zip
        if rail_zip_path is None and not args.no_rail:
            rail_zip_path = download_rail_gtfs_with_fallback(Path(tmp) / "rail_gtfs.zip")

        meta = build_database(zip_path, args.output_dir, rail_zip_path=rail_zip_path)
        print("Build complete:", json.dumps(meta, indent=2))


if __name__ == "__main__":
    main()
