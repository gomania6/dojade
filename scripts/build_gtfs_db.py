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
import struct
import tempfile
import urllib.request
import zipfile
from collections import defaultdict
from pathlib import Path

try:
    from zoneinfo import ZoneInfo
except ImportError:  # pragma: no cover
    ZoneInfo = None  # type: ignore

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

ROOM_DB_VERSION = 4
ROOM_IDENTITY_HASH = "dc4426e9d6a7b832b5461a17d983f804"
SHAPE_SIMPLIFY_METERS = 8.0
RAIL_WINDOW_DAYS = 21

RAPTOR_CACHE_MAGIC = 0x4F445243  # "ODRC" (совпадает с RaptorDataCache.kt)
RAPTOR_CACHE_VERSION = 1
RAPTOR_BUNDLE_MAGIC = 0x4F445242  # "ODRB" (OffDojade Raptor Bundle)
RAPTOR_BUNDLE_VERSION = 1
RAPTOR_PREBUILD_DAYS = 7

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


def simplify_shape_dp(points: list[tuple[int, float, float]], epsilon_m: float = SHAPE_SIMPLIFY_METERS) -> list[tuple[int, float, float]]:
    """
    Упрощает полилинию `shapes.txt` методом Дугласа-Пекера (порог ~8 м):
    визуально линия остаётся идентичной на карте города, но число точек сокращается на 70–80%,
    что резко уменьшает размер `poznan_schedule.db.gz` и ускоряет первую загрузку.
    """
    n = len(points)
    if n <= 2:
        return points

    # Локальная эквиректангулярная проекция в метрах для широты Познани (~52.4°)
    cos_lat = math.cos(math.radians(POZNAN_CENTER_LAT))
    m_per_deg_lat = 111_320.0
    m_per_deg_lon = 111_320.0 * cos_lat
    eps_sq = epsilon_m * epsilon_m

    xs = [p[2] * m_per_deg_lon for p in points]
    ys = [p[1] * m_per_deg_lat for p in points]

    keep = bytearray(n)
    keep[0] = 1
    keep[n - 1] = 1
    stack = [(0, n - 1)]

    while stack:
        start, end = stack.pop()
        if end - start <= 1:
            continue
        x1, y1 = xs[start], ys[start]
        x2, y2 = xs[end], ys[end]
        dx = x2 - x1
        dy = y2 - y1
        seg_len_sq = dx * dx + dy * dy

        max_dist_sq = -1.0
        max_idx = -1
        for i in range(start + 1, end):
            px, py = xs[i], ys[i]
            if seg_len_sq == 0.0:
                d_sq = (px - x1) ** 2 + (py - y1) ** 2
            else:
                t = ((px - x1) * dx + (py - y1) * dy) / seg_len_sq
                if t < 0.0:
                    proj_x, proj_y = x1, y1
                elif t > 1.0:
                    proj_x, proj_y = x2, y2
                else:
                    proj_x = x1 + t * dx
                    proj_y = y1 + t * dy
                d_sq = (px - proj_x) ** 2 + (py - proj_y) ** 2
            if d_sq > max_dist_sq:
                max_dist_sq = d_sq
                max_idx = i

        if max_dist_sq > eps_sq and max_idx != -1:
            keep[max_idx] = 1
            stack.append((start, max_idx))
            stack.append((max_idx, end))

    return [points[i] for i in range(n) if keep[i]]


def init_room_schema(conn: sqlite3.Connection) -> None:
    cur = conn.cursor()
    cur.executescript(
        f"""
        PRAGMA page_size = 8192;
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

        # Фильтруем ж/д сервисы по скользящему окну [вчера .. +21 день], чтобы не раздувать БД
        # месяцами далёких/устаревших расписаний PKP (с безопасным fallback если окно пустое)
        today_warsaw = get_warsaw_today()
        win_start_int = int((today_warsaw - dt.timedelta(days=1)).strftime("%Y%m%d"))
        win_end_int = int((today_warsaw + dt.timedelta(days=RAIL_WINDOW_DAYS)).strftime("%Y%m%d"))
        window_service_ids: set[str] = set()
        for r in read_zip_csv(zf, "calendar.txt"):
            sid = r.get("service_id", "")
            if not sid:
                continue
            try:
                sd, ed = int(r["start_date"]), int(r["end_date"])
            except (KeyError, ValueError):
                continue
            if ed >= win_start_int and sd <= win_end_int:
                window_service_ids.add(sid)
        for r in read_zip_csv(zf, "calendar_dates.txt"):
            sid = r.get("service_id", "")
            if not sid:
                continue
            try:
                d_int = int(r["date"])
                ex_type = int(r["exception_type"])
            except (KeyError, ValueError):
                continue
            if ex_type == 1 and win_start_int <= d_int <= win_end_int:
                window_service_ids.add(sid)

        if window_service_ids:
            filtered_trips = {tid: t for tid, t in raw_trips.items() if t["service_id"] in window_service_ids}
            if filtered_trips:
                raw_trips = filtered_trips

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

        # Сохраняем только точки геометрии shapes внутри расширенного bbox Познани с упрощением Дугласа-Пекера
        if used_shape_ids:
            rail_shapes: dict[str, list[tuple[int, float, float]]] = defaultdict(list)
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
                    rail_shapes[shid].append((seq, lat, lon))
            shape_batch = []
            for shid, pts in rail_shapes.items():
                pts.sort(key=lambda x: x[0])
                simplified = simplify_shape_dp(pts)
                pref_shid = RAIL_ID_PREFIX + shid
                for seq, lat, lon in simplified:
                    shape_batch.append((pref_shid, seq, round(lat, 5), round(lon, 5)))
                if len(shape_batch) >= 10_000:
                    cur.executemany("INSERT OR REPLACE INTO shapes VALUES (?, ?, ?, ?)", shape_batch)
                    shape_batch.clear()
            if shape_batch:
                cur.executemany("INSERT OR REPLACE INTO shapes VALUES (?, ?, ?, ?)", shape_batch)

        return len(stops_rows), len(trips_rows)


def get_warsaw_today() -> dt.date:
    if ZoneInfo is not None:
        try:
            return dt.datetime.now(ZoneInfo("Europe/Warsaw")).date()
        except Exception:
            pass
    return (dt.datetime.now(dt.timezone.utc) + dt.timedelta(hours=1)).date()


def encode_java_utf(s: str) -> bytes:
    """Кодирует строку в формат Java DataOutputStream.writeUTF (2-byte big-endian len + Modified UTF-8)."""
    out = bytearray()
    for ch in s:
        cp = ord(ch)
        if 0x0001 <= cp <= 0x007F:
            out.append(cp)
        elif cp == 0x0000 or (0x0080 <= cp <= 0x07FF):
            out.append(0xC0 | ((cp >> 6) & 0x1F))
            out.append(0x80 | (cp & 0x3F))
        elif cp <= 0xFFFF:
            out.append(0xE0 | ((cp >> 12) & 0x0F))
            out.append(0x80 | ((cp >> 6) & 0x3F))
            out.append(0x80 | (cp & 0x3F))
        else:
            # Surrogate pair in Modified UTF-8
            cp2 = cp - 0x10000
            hi = 0xD800 + (cp2 >> 10)
            lo = 0xDC00 + (cp2 & 0x3FF)
            for sur in (hi, lo):
                out.append(0xE0 | ((sur >> 12) & 0x0F))
                out.append(0x80 | ((sur >> 6) & 0x3F))
                out.append(0x80 | (sur & 0x3F))
    return struct.pack(">H", len(out)) + bytes(out)


def _pack_int_array(values: list[int]) -> bytes:
    if not values:
        return struct.pack(">i", 0)
    return struct.pack(f">i{len(values)}i", len(values), *values)


def _pack_double_array(values: list[float]) -> bytes:
    if not values:
        return struct.pack(">i", 0)
    return struct.pack(f">i{len(values)}d", len(values), *values)


def _pack_string_array(values: list[str]) -> bytes:
    parts = [struct.pack(">i", len(values))]
    for s in values:
        parts.append(encode_java_utf(s))
    return b"".join(parts)


def _pack_nullable_string_array(values: list[str | None]) -> bytes:
    parts = [struct.pack(">i", len(values))]
    for s in values:
        if s is None:
            parts.append(b"\x00")
        else:
            parts.append(b"\x01")
            parts.append(encode_java_utf(s))
    return b"".join(parts)


def _resolve_active_services_for_date(
    target_date: dt.date,
    calendars: list[tuple],
    cal_dates_by_date: dict[int, list[tuple[str, int]]],
) -> list[str]:
    """
    Полный аналог TransitGraphRepository.resolveActiveServicesForDate:
    резолвит ZTM и ж/д (`rail_`) независимо, с раздельными фолбэками.
    """
    date_int = int(target_date.strftime("%Y%m%d"))
    dow_idx = target_date.weekday()  # 0=Mon .. 6=Sun

    exact_active: set[str] = set()
    for cal in calendars:
        # (service_id, mon, tue, wed, thu, fri, sat, sun, start_date, end_date)
        if cal[8] <= date_int <= cal[9] and cal[1 + dow_idx] == 1:
            exact_active.add(cal[0])

    for sid, ex_type in cal_dates_by_date.get(date_int, ()):
        if ex_type == 1:
            exact_active.add(sid)
        elif ex_type == 2:
            exact_active.discard(sid)

    ztm_active = {s for s in exact_active if not s.startswith(RAIL_ID_PREFIX)}
    rail_active = {s for s in exact_active if s.startswith(RAIL_ID_PREFIX)}

    if not ztm_active:
        ztm_cals = [c for c in calendars if not c[0].startswith(RAIL_ID_PREFIX)]
        by_dow = [c[0] for c in ztm_cals if c[1 + dow_idx] == 1]
        ztm_active.update(by_dow if by_dow else [c[0] for c in ztm_cals])

    if not rail_active:
        rail_cals = [c for c in calendars if c[0].startswith(RAIL_ID_PREFIX)]
        by_cal = [c[0] for c in rail_cals if c[1 + dow_idx] == 1]
        rail_active.update(by_cal)
        if not rail_active:
            for delta in (7, -7, 1, -1):
                probe_int = int((target_date + dt.timedelta(days=delta)).strftime("%Y%m%d"))
                probe_rail = [
                    sid for sid, ex_type in cal_dates_by_date.get(probe_int, ())
                    if sid.startswith(RAIL_ID_PREFIX) and ex_type == 1
                ]
                if probe_rail:
                    rail_active.update(probe_rail)
                    break
        if not rail_active:
            avail_rail_dates = [
                (dt.date(d // 10000, (d // 100) % 100, d % 100), d)
                for d, entries in cal_dates_by_date.items()
                if any(sid.startswith(RAIL_ID_PREFIX) and ex_type == 1 for sid, ex_type in entries)
            ]
            if avail_rail_dates:
                same_dow = [pair for pair in avail_rail_dates if pair[0].weekday() == dow_idx]
                pool = same_dow if same_dow else avail_rail_dates
                nearest_d = min(pool, key=lambda pair: abs((pair[0] - target_date).days))[1]
                rail_active.update(
                    sid for sid, ex_type in cal_dates_by_date.get(nearest_d, ())
                    if sid.startswith(RAIL_ID_PREFIX) and ex_type == 1
                )

    return sorted(ztm_active | rail_active)


def _serialize_raptor_graph_for_services(
    conn: sqlite3.Connection,
    service_ids: list[str],
    stops: list[tuple],
    stop_id_to_index: dict[str, int],
    route_by_id: dict[str, tuple],
    footpath_offsets: list[int],
    footpath_to_stop: list[int],
    footpath_walk_sec: list[int],
    footpath_distance_meters: list[int],
) -> bytes:
    """
    Строит граф RaptorData (100% эквивалент TransitGraphBuilder.kt) и сериализует его
    в бинарный формат RaptorDataCache v1 (MAGIC=0x4F445243, FORMAT_VERSION=1).
    Поля dbFingerprint (offset 8..15) и serviceDateYyyymmdd (offset 16..19) заполняются 0
    и патчатся на устройстве при распаковке бандла под актуальный fingerprint БД.
    """
    cur = conn.cursor()
    trips: list[tuple] = []
    for i in range(0, len(service_ids), 500):
        chunk = service_ids[i:i + 500]
        ph = ",".join("?" for _ in chunk)
        cur.execute(
            f"SELECT trip_id, route_id, trip_headsign, shape_id, wheelchair_accessible "
            f"FROM trips WHERE service_id IN ({ph})",
            chunk,
        )
        trips.extend(cur.fetchall())

    trip_by_id = {t[0]: t for t in trips}

    stop_times_by_trip: dict[str, list[tuple[int, int, int, int, int]]] = defaultdict(list)
    for i in range(0, len(service_ids), 500):
        chunk = service_ids[i:i + 500]
        ph = ",".join("?" for _ in chunk)
        cur.execute(
            f"SELECT st.trip_id, st.stop_sequence, st.stop_id, st.arrival_time_sec, st.departure_time_sec, st.is_on_demand "
            f"FROM stop_times st INNER JOIN trips t ON st.trip_id = t.trip_id "
            f"WHERE t.service_id IN ({ph})",
            chunk,
        )
        for tid, seq, sid, arr, dep, on_demand in cur:
            s_idx = stop_id_to_index.get(sid)
            if s_idx is None or tid not in trip_by_id:
                continue
            stop_times_by_trip[tid].append((seq, s_idx, arr, dep, 1 if on_demand == 1 else 0))

    # Группируем рейсы в паттерны с идентичной последовательностью остановок
    pattern_map: dict[tuple[str, tuple[int, ...]], list[tuple[tuple, list[tuple[int, int, int, int, int]]]]] = {}
    for tid, st_list in stop_times_by_trip.items():
        if len(st_list) < 2:
            continue
        st_list.sort(key=lambda x: x[0])
        trip = trip_by_id[tid]
        stop_seq = tuple(st[1] for st in st_list)
        key = (trip[1], stop_seq)
        pattern_map.setdefault(key, []).append((trip, st_list))

    entries = list(pattern_map.items())
    pattern_count = len(entries)
    pattern_stop_offsets = [0] * (pattern_count + 1)
    pattern_trip_offsets = [0] * (pattern_count + 1)
    pattern_route_short_name: list[str] = [""] * pattern_count
    pattern_route_type: list[int] = [3] * pattern_count
    pattern_route_color: list[str | None] = [None] * pattern_count
    pattern_headsign: list[str] = [""] * pattern_count

    total_pattern_stops = 0
    total_trips = 0
    total_st_entries = 0

    for p, ((route_id, stop_seq), p_trips) in enumerate(entries):
        p_trips.sort(key=lambda item: item[1][0][3])  # first stop departure_time_sec
        pattern_stop_offsets[p] = total_pattern_stops
        pattern_trip_offsets[p] = total_trips
        total_pattern_stops += len(stop_seq)
        total_trips += len(p_trips)
        total_st_entries += len(p_trips) * len(stop_seq)

        route = route_by_id.get(route_id)
        if route is not None:
            pattern_route_short_name[p] = route[1]
            pattern_route_type[p] = route[3]
            pattern_route_color[p] = route[4]
            route_long_name = route[2] or ""
        else:
            pattern_route_short_name[p] = route_id
            pattern_route_type[p] = 3
            pattern_route_color[p] = None
            route_long_name = ""

        first_hs = p_trips[0][0][2] if p_trips else ""
        pattern_headsign[p] = first_hs if first_hs.strip() else route_long_name

    pattern_stop_offsets[pattern_count] = total_pattern_stops
    pattern_trip_offsets[pattern_count] = total_trips

    stop_count = len(stops)
    pattern_stops: list[int] = []
    trip_ids: list[str] = []
    trip_shape_ids: list[str] = []
    trip_pattern_index: list[int] = []
    trip_wheelchair: list[int] = []
    trip_st_offsets: list[int] = []
    arrival_times: list[int] = []
    departure_times: list[int] = []
    on_demand_bytes = bytearray(total_st_entries)
    stop_pattern_counts = [0] * stop_count

    st_cursor = 0
    for p, ((_, stop_seq), p_trips) in enumerate(entries):
        for s_idx in stop_seq:
            pattern_stops.append(s_idx)
            stop_pattern_counts[s_idx] += 1
        for trip, st_list in p_trips:
            trip_ids.append(trip[0])
            trip_shape_ids.append(trip[3] or "")
            trip_pattern_index.append(p)
            trip_wheelchair.append(trip[4])
            trip_st_offsets.append(st_cursor)
            for _, _, arr, dep, on_dem in st_list:
                arrival_times.append(arr)
                departure_times.append(dep)
                on_demand_bytes[st_cursor] = on_dem
                st_cursor += 1
    trip_st_offsets.append(st_cursor)

    # CSR reverse index: stop -> (pattern, pos)
    stop_to_pattern_offsets = [0] * (stop_count + 1)
    total_sp = 0
    for s in range(stop_count):
        stop_to_pattern_offsets[s] = total_sp
        total_sp += stop_pattern_counts[s]
    stop_to_pattern_offsets[stop_count] = total_sp

    stop_to_patterns = [0] * total_sp
    stop_to_pattern_pos = [0] * total_sp
    stop_fill = list(stop_to_pattern_offsets)
    for p in range(pattern_count):
        p_start = pattern_stop_offsets[p]
        p_end = pattern_stop_offsets[p + 1]
        for pos in range(p_end - p_start):
            s_idx = pattern_stops[p_start + pos]
            w_idx = stop_fill[s_idx]
            stop_fill[s_idx] = w_idx + 1
            stop_to_patterns[w_idx] = p
            stop_to_pattern_pos[w_idx] = pos

    stop_ids = [s[0] for s in stops]
    stop_names = [s[1] for s in stops]
    stop_lats = [float(s[2]) for s in stops]
    stop_lons = [float(s[3]) for s in stops]
    stop_zones = [(s[4] if (s[4] and s[4].strip()) else "A") for s in stops]
    stop_platforms = [s[5] or "" for s in stops]

    chunks = [
        # Header: MAGIC, FORMAT_VERSION, placeholder dbFingerprint (0L), placeholder serviceDate (0)
        struct.pack(">iiqi", RAPTOR_CACHE_MAGIC, RAPTOR_CACHE_VERSION, 0, 0),
        # Stops
        struct.pack(">i", stop_count),
        _pack_string_array(stop_ids),
        _pack_string_array(stop_names),
        _pack_double_array(stop_lats),
        _pack_double_array(stop_lons),
        _pack_string_array(stop_zones),
        _pack_string_array(stop_platforms),
        # Patterns
        struct.pack(">i", pattern_count),
        _pack_int_array(pattern_stop_offsets),
        _pack_int_array(pattern_stops),
        _pack_int_array(pattern_trip_offsets),
        _pack_string_array(pattern_route_short_name),
        _pack_int_array(pattern_route_type),
        _pack_nullable_string_array(pattern_route_color),
        _pack_string_array(pattern_headsign),
        # Trips
        struct.pack(">i", total_trips),
        _pack_string_array(trip_ids),
        _pack_string_array(trip_shape_ids),
        _pack_int_array(trip_pattern_index),
        _pack_int_array(trip_wheelchair),
        _pack_int_array(trip_st_offsets),
        _pack_int_array(arrival_times),
        _pack_int_array(departure_times),
        struct.pack(">i", len(on_demand_bytes)),
        bytes(on_demand_bytes),
        # Reverse index
        _pack_int_array(stop_to_pattern_offsets),
        _pack_int_array(stop_to_patterns),
        _pack_int_array(stop_to_pattern_pos),
        # Footpaths
        _pack_int_array(footpath_offsets),
        _pack_int_array(footpath_to_stop),
        _pack_int_array(footpath_walk_sec),
        _pack_int_array(footpath_distance_meters),
    ]
    return b"".join(chunks)


def build_raptor_bundle(db_path: Path, output_dir: Path, num_days: int = RAPTOR_PREBUILD_DAYS) -> dict:
    """
    Заранее формирует бинарные графы RAPTOR на ближайшие `num_days` дней (с дедупликацией
    одинаковых расписаний Пн–Пт) и упаковывает их в `raptor_graphs.bundle.gz`.
    На телефоне распаковка занимает < 200 мс, полностью исключая первичную сборку графа из SQLite!
    """
    bundle_path = output_dir / "raptor_graphs.bundle.gz"
    conn = sqlite3.connect(str(db_path))
    cur = conn.cursor()

    cur.execute("SELECT stop_id, stop_name, stop_lat, stop_lon, zone_id, platform_code FROM stops ORDER BY stop_name ASC")
    stops = cur.fetchall()
    stop_id_to_index = {s[0]: i for i, s in enumerate(stops)}
    stop_count = len(stops)

    cur.execute("SELECT route_id, short_name, long_name, route_type, route_color FROM routes")
    route_by_id = {r[0]: r for r in cur.fetchall()}

    cur.execute("SELECT service_id, monday, tuesday, wednesday, thursday, friday, saturday, sunday, start_date, end_date FROM calendars")
    calendars = cur.fetchall()

    cal_dates_by_date: dict[int, list[tuple[str, int]]] = defaultdict(list)
    cur.execute("SELECT service_id, date, exception_type FROM calendar_dates")
    for sid, d_int, ex_type in cur.fetchall():
        cal_dates_by_date[d_int].append((sid, ex_type))

    # Предрасчитываем CSR-массивы пересадок один раз (они общие для всех дней)
    fp_by_stop: list[list[tuple[int, int, int]]] = [[] for _ in range(stop_count)]
    cur.execute("SELECT from_stop_id, to_stop_id, min_transfer_time_sec, distance_meters FROM transfers")
    for from_id, to_id, min_sec, dist_m in cur.fetchall():
        f_idx = stop_id_to_index.get(from_id)
        t_idx = stop_id_to_index.get(to_id)
        if f_idx is None or t_idx is None or f_idx == t_idx:
            continue
        fp_by_stop[f_idx].append((t_idx, max(30, int(min_sec)), max(10, int(dist_m))))

    footpath_offsets = [0] * (stop_count + 1)
    footpath_to_stop: list[int] = []
    footpath_walk_sec: list[int] = []
    footpath_distance_meters: list[int] = []
    cursor = 0
    for s in range(stop_count):
        footpath_offsets[s] = cursor
        for t_idx, w_sec, d_m in fp_by_stop[s]:
            footpath_to_stop.append(t_idx)
            footpath_walk_sec.append(w_sec)
            footpath_distance_meters.append(d_m)
            cursor += 1
    footpath_offsets[stop_count] = cursor

    today = get_warsaw_today()
    date_entries: list[tuple[int, int]] = []  # (dateYyyymmdd, unique_graph_idx)
    service_set_to_idx: dict[tuple[str, ...], int] = {}
    unique_service_sets: list[list[str]] = []

    for offset in range(num_days):
        target_date = today + dt.timedelta(days=offset)
        date_int = int(target_date.strftime("%Y%m%d"))
        active = _resolve_active_services_for_date(target_date, calendars, cal_dates_by_date)
        key = tuple(active)
        if key not in service_set_to_idx:
            service_set_to_idx[key] = len(unique_service_sets)
            unique_service_sets.append(active)
        date_entries.append((date_int, service_set_to_idx[key]))

    unique_payloads: list[bytes] = []
    for svc_ids in unique_service_sets:
        payload = _serialize_raptor_graph_for_services(
            conn=conn,
            service_ids=svc_ids,
            stops=stops,
            stop_id_to_index=stop_id_to_index,
            route_by_id=route_by_id,
            footpath_offsets=footpath_offsets,
            footpath_to_stop=footpath_to_stop,
            footpath_walk_sec=footpath_walk_sec,
            footpath_distance_meters=footpath_distance_meters,
        )
        unique_payloads.append(payload)

    conn.close()

    with gzip.open(bundle_path, "wb", compresslevel=9) as gz:
        gz.write(struct.pack(">iii", RAPTOR_BUNDLE_MAGIC, RAPTOR_BUNDLE_VERSION, len(date_entries)))
        for date_int, g_idx in date_entries:
            gz.write(struct.pack(">ii", date_int, g_idx))
        gz.write(struct.pack(">i", len(unique_payloads)))
        for payload in unique_payloads:
            gz.write(struct.pack(">i", len(payload)))
            gz.write(payload)

    return {
        "raptor_bundle_filename": bundle_path.name,
        "raptor_bundle_sha256": sha256_file(bundle_path),
        "raptor_bundle_size_bytes": bundle_path.stat().st_size,
        "raptor_bundle_dates": [d for d, _ in date_entries],
        "raptor_bundle_unique_graphs": len(unique_payloads),
    }


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
        used_ztm_shape_ids: set[str] = set()
        for r in read_zip_csv(zf, "trips.txt"):
            tid = r.get("trip_id", "")
            rid = r.get("route_id", "")
            sid = r.get("service_id", "")
            if not (tid and rid and sid):
                continue
            valid_trip_ids.add(tid)
            shid = r.get("shape_id", "")
            if shid:
                used_ztm_shape_ids.add(shid)
            trips.append((
                tid,
                rid,
                sid,
                r.get("trip_headsign", ""),
                int(r.get("direction_id") or 0),
                shid,
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

        ztm_shapes: dict[str, list[tuple[int, float, float]]] = defaultdict(list)
        for r in read_zip_csv(zf, "shapes.txt"):
            shid = r.get("shape_id", "")
            if not shid or (used_ztm_shape_ids and shid not in used_ztm_shape_ids):
                continue
            try:
                ztm_shapes[shid].append((int(r["shape_pt_sequence"]), float(r["shape_pt_lat"]), float(r["shape_pt_lon"])))
            except (KeyError, ValueError):
                continue
        shape_batch = []
        for shid, pts in ztm_shapes.items():
            pts.sort(key=lambda x: x[0])
            for seq, lat, lon in simplify_shape_dp(pts):
                shape_batch.append((shid, seq, round(lat, 5), round(lon, 5)))
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
    print(f"Pre-building {RAPTOR_PREBUILD_DAYS}-day binary RAPTOR graph bundle...")
    bundle_meta = build_raptor_bundle(db_path, output_dir, num_days=RAPTOR_PREBUILD_DAYS)
    print(
        f"RAPTOR bundle ready: {bundle_meta['raptor_bundle_filename']} "
        f"({bundle_meta['raptor_bundle_size_bytes'] / (1024 * 1024):.2f} MB, "
        f"{bundle_meta['raptor_bundle_unique_graphs']} unique graphs for {len(bundle_meta['raptor_bundle_dates'])} days)"
    )

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
        **bundle_meta,
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
