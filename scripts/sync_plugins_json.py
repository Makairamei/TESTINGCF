#!/usr/bin/env python3
"""
sync_plugins_json.py
====================
Dijalankan oleh GitHub Actions SETELAH semua plugin selesai di-build.

- Membaca builds/plugins.json (output dari makePluginsJson Gradle)
- Menyinkronkan fileHash & fileSize semua plugin dari file .cs3 aktual di builds/
- Menambahkan plugin "extra" yang tidak terdaftar di makePluginsJson (misal: tidak punya annotation lengkap)
- Menulis plugins.json (root) dan builds/plugins.json baru dengan UTF-8 tanpa BOM
"""

import json
import hashlib
import os
import sys

BUILDS_DIR = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "builds")
ROOT_JSON  = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "plugins.json")
BUILDS_JSON = os.path.join(BUILDS_DIR, "plugins.json")
REPO_URL   = "https://github.com/Makairamei/TESTINGCF"
RAW_BASE   = "https://raw.githubusercontent.com/Makairamei/TESTINGCF/master/builds/"

# ── Metadata untuk plugin yang tidak terdeteksi makePluginsJson ──────────────
EXTRA_PLUGINS = [
    {
        "internalName": "MovieBoxProvider",
        "name": "MovieBoxProvider",
        "description": "Multi Language Movies and Series Provider",
        "authors": ["sad25kag"],
        "tvTypes": ["Movie", "TvSeries"],
        "language": "id",
        "status": 1,
        "version": 18,
        "iconUrl": "https://github.com/NivinCNC/CNCVerse-Cloud-Stream-Extension/raw/refs/heads/master/MovieBoxProvider/icon.png",
    },
    {
        "internalName": "NgeFilm21Provider",
        "name": "NgeFilm21Provider",
        "description": "NgeFilm21 - Streaming Movie and TV Series",
        "authors": ["sad25kag"],
        "tvTypes": ["AsianDrama", "TvSeries", "Movie"],
        "language": "id",
        "status": 1,
        "version": 1,
        "iconUrl": "https://new31.ngefilm.site/wp-content/uploads/2023/08/cropped-imageedit_8_4481000408-60x60.png",
    },
    {
        "internalName": "NontonAnimeIDProvider",
        "name": "NontonAnimeIDProvider",
        "description": "NontonAnimeID - Streaming Anime Subtitle Indonesia",
        "authors": ["sad25kag"],
        "tvTypes": ["AnimeMovie", "Anime", "OVA"],
        "language": "id",
        "status": 1,
        "version": 3,
        "iconUrl": "https://t2.gstatic.com/faviconV2?client=SOCIAL&type=FAVICON&fallback_opts=TYPE,SIZE,URL&url=https://s11.nontonanimeid.boats&size=%size%",
    },
    {
        "internalName": "XSmoviebox",
        "name": "XSmoviebox",
        "description": "XSmoviebox",
        "authors": ["BetbetMiro"],
        "tvTypes": ["AsianDrama", "TvSeries", "Movie"],
        "language": "id",
        "status": 1,
        "version": 6,
        "iconUrl": "https://moviebox.ph/favicon.ico",
    },
    {
        "internalName": "MovieOn21",
        "name": "MovieOn21",
        "description": "MovieOn21 provider",
        "authors": ["sad25kag"],
        "tvTypes": ["Movie", "TvSeries", "AsianDrama"],
        "language": "id",
        "status": 1,
        "version": 6,
        "iconUrl": "https://www.google.com/s2/favicons?domain=tv.movieon21.mov&sz=%size%",
    },
    {
        "internalName": "DramaIdProvider",
        "name": "DramaIdProvider",
        "description": "DramaID - drama Asia subtitle Indonesia.",
        "authors": ["sad25kag"],
        "tvTypes": ["AsianDrama", "TvSeries", "Movie"],
        "language": "id",
        "status": 1,
        "version": 15,
        "iconUrl": "https://t2.gstatic.com/faviconV2?client=SOCIAL&type=FAVICON&fallback_opts=TYPE,SIZE,URL&url=https://drama-id.com&size=%size%",
    },
    {
        "internalName": "JuraganFilm",
        "name": "JuraganFilm",
        "description": "JuraganFilm provider for tv45.juragan.film",
        "authors": ["sad25kag"],
        "tvTypes": ["Movie", "TvSeries", "Anime", "AsianDrama"],
        "language": "id",
        "status": 1,
        "version": 19,
        "iconUrl": "https://www.google.com/s2/favicons?domain=tv45.juragan.film&sz=%size%",
    },
]

def sha256_hash(path: str) -> str:
    data = open(path, "rb").read()
    return "sha256-" + hashlib.sha256(data).hexdigest()

def file_size(path: str) -> int:
    return os.path.getsize(path)

def main():
    # Baca builds/plugins.json
    if os.path.exists(BUILDS_JSON):
        with open(BUILDS_JSON, "r", encoding="utf-8") as f:
            plugins = json.load(f)
        print(f"[sync] builds/plugins.json: {len(plugins)} entries")
    else:
        plugins = []
        print("[sync] builds/plugins.json tidak ada, mulai dari daftar kosong")

    existing = {p["internalName"] for p in plugins}

    # Sinkronkan hash & size semua plugin yang ada
    updated = 0
    for p in plugins:
        cs3 = os.path.join(BUILDS_DIR, p["internalName"] + ".cs3")
        if os.path.exists(cs3):
            new_hash = sha256_hash(cs3)
            new_size = file_size(cs3)
            if p.get("fileHash") != new_hash or p.get("fileSize") != new_size:
                p["fileHash"] = new_hash
                p["fileSize"] = new_size
                updated += 1
            # Pastikan URL pakai path master
            p["url"] = RAW_BASE + p["internalName"] + ".cs3"
            p["repositoryUrl"] = REPO_URL
    print(f"[sync] Updated {updated} hashes dari builds aktual")

    # Tambahkan extra plugins yang belum terdaftar
    added = 0
    for e in EXTRA_PLUGINS:
        iname = e["internalName"]
        if iname in existing:
            continue
        cs3 = os.path.join(BUILDS_DIR, iname + ".cs3")
        if not os.path.exists(cs3):
            print(f"[sync] SKIP (no cs3): {iname}")
            continue
        entry = {
            "iconUrl":       e["iconUrl"],
            "fileHash":      sha256_hash(cs3),
            "apiVersion":    1,
            "repositoryUrl": REPO_URL,
            "fileSize":      file_size(cs3),
            "status":        e["status"],
            "language":      e["language"],
            "authors":       e["authors"],
            "tvTypes":       e["tvTypes"],
            "version":       e["version"],
            "internalName":  iname,
            "description":   e["description"],
            "url":           RAW_BASE + iname + ".cs3",
            "name":          e["name"],
        }
        plugins.append(entry)
        print(f"[sync] Added: {iname}")
        added += 1

    print(f"[sync] Total: {len(plugins)} plugin ({added} baru ditambahkan)")

    # Tulis plugins.json (root) – UTF-8 tanpa BOM, LF endings
    out = json.dumps(plugins, indent=4, ensure_ascii=False)
    for path in [ROOT_JSON, BUILDS_JSON]:
        with open(path, "w", encoding="utf-8", newline="\n") as f:
            f.write(out)

    # Validasi
    check = json.loads(out)
    first_bytes = open(ROOT_JSON, "rb").read(3)
    print(f"[sync] JSON valid: {len(check)} plugins")
    print(f"[sync] First 3 bytes: {[hex(b) for b in first_bytes]} (expect 0x5b ...)")
    if first_bytes[0] == 0xEF:
        print("[sync] ERROR: BOM masih ada!")
        sys.exit(1)
    print("[sync] DONE")

if __name__ == "__main__":
    main()
