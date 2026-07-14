#!/usr/bin/env python3
import json, hashlib, os, re, sys

BUILDS_DIR  = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "builds")
ROOT_JSON   = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "plugins.json")
BUILDS_JSON = os.path.join(BUILDS_DIR, "plugins.json")
REPO_ROOT   = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REPO_URL    = "https://github.com/Makairamei/TESTINGCF"
RAW_BASE    = "https://raw.githubusercontent.com/Makairamei/TESTINGCF/master/builds/"

EXTRA_PLUGINS = [
    {"internalName":"MovieBoxProvider","name":"MovieBoxProvider","description":"Multi Language Movies and Series Provider","authors":["sad25kag"],"tvTypes":["Movie","TvSeries"],"language":"id","status":1,"iconUrl":"https://github.com/NivinCNC/CNCVerse-Cloud-Stream-Extension/raw/refs/heads/master/MovieBoxProvider/icon.png"},
    {"internalName":"NgeFilm21Provider","name":"NgeFilm21Provider","description":"NgeFilm21 - Streaming Movie and TV Series","authors":["sad25kag"],"tvTypes":["AsianDrama","TvSeries","Movie"],"language":"id","status":1,"iconUrl":"https://new31.ngefilm.site/wp-content/uploads/2023/08/cropped-imageedit_8_4481000408-60x60.png"},
    {"internalName":"NontonAnimeIDProvider","name":"NontonAnimeIDProvider","description":"NontonAnimeID - Streaming Anime Subtitle Indonesia","authors":["sad25kag"],"tvTypes":["AnimeMovie","Anime","OVA"],"language":"id","status":1,"iconUrl":"https://t2.gstatic.com/faviconV2?client=SOCIAL&type=FAVICON&fallback_opts=TYPE,SIZE,URL&url=https://s11.nontonanimeid.boats&size=%size%"},
    {"internalName":"XSmoviebox","name":"XSmoviebox","description":"XSmoviebox","authors":["BetbetMiro"],"tvTypes":["AsianDrama","TvSeries","Movie"],"language":"id","status":1,"iconUrl":"https://moviebox.ph/favicon.ico"},
    {"internalName":"MovieOn21","name":"MovieOn21","description":"MovieOn21 provider","authors":["sad25kag"],"tvTypes":["Movie","TvSeries","AsianDrama"],"language":"id","status":1,"iconUrl":"https://www.google.com/s2/favicons?domain=tv.movieon21.mov&sz=%size%"},
    {"internalName":"JuraganFilm","name":"JuraganFilm","description":"JuraganFilm provider for tv45.juragan.film","authors":["sad25kag"],"tvTypes":["Movie","TvSeries","Anime","AsianDrama"],"language":"id","status":1,"iconUrl":"https://www.google.com/s2/favicons?domain=tv45.juragan.film&sz=%size%"},
    {"internalName":"Oppadrama","name":"Oppadrama","description":"Oppadrama provider","authors":["sad25kag"],"tvTypes":["AsianDrama","Movie","TvSeries"],"language":"id","status":1,"iconUrl":"https://www.google.com/s2/favicons?domain=oppadrama.co&sz=%size%"},
    {"internalName":"PencuriMovieProvider","name":"PencuriMovieProvider","description":"PencuriMovie provider","authors":["sad25kag"],"tvTypes":["Movie","Anime","Cartoon"],"language":"id","status":1,"iconUrl":"https://www.google.com/s2/favicons?domain=ww99.pencurimovie.bond&sz=%size%"},
    {"internalName":"Pusatfilm","name":"Pusatfilm","description":"Pusatfilm provider","authors":["sad25kag"],"tvTypes":["Movie","TvSeries"],"language":"id","status":1,"iconUrl":"https://www.google.com/s2/favicons?domain=pusatfilm.cam&sz=%size%"},
    {"internalName":"Samehadaku","name":"Samehadaku","description":"Samehadaku provider","authors":["sad25kag"],"tvTypes":["Anime","AnimeMovie","OVA"],"language":"id","status":1,"iconUrl":"https://www.google.com/s2/favicons?domain=samehadaku.email&sz=%size%"},
]

# Plugin yang dihapus — tidak akan masuk ke plugins.json
DISABLED_PLUGINS = {"DailymotionProvider", "DrakorKita", "DramaIdProvider"}

def sha256_hash(path):
    return "sha256-" + hashlib.sha256(open(path,"rb").read()).hexdigest()

def file_size(path):
    return os.path.getsize(path)

def read_version_from_gradle(internal_name):
    gradle_path = os.path.join(REPO_ROOT, internal_name, "build.gradle.kts")
    if not os.path.exists(gradle_path):
        return 1
    try:
        content = open(gradle_path,"r",encoding="utf-8").read()
        m = re.search(r"^\s*version\s*=\s*(\d+)", content, re.MULTILINE)
        if m:
            return int(m.group(1))
    except Exception:
        pass
    return 1

def main():
    if os.path.exists(BUILDS_JSON):
        with open(BUILDS_JSON,"r",encoding="utf-8") as f:
            plugins = json.load(f)
        print(f"[sync] builds/plugins.json: {len(plugins)} entries")
    else:
        plugins = []
        print("[sync] builds/plugins.json tidak ada, mulai dari kosong")

    existing = {p["internalName"] for p in plugins}

    # Filter keluar plugin yang sudah di-disable
    before = len(plugins)
    plugins = [p for p in plugins if p.get("internalName", "") not in DISABLED_PLUGINS]
    if len(plugins) < before:
        print(f"[sync] Removed {before - len(plugins)} disabled plugins: {DISABLED_PLUGINS}")
    existing = {p["internalName"] for p in plugins}

    updated = 0
    for p in plugins:
        iname = p.get("internalName","")
        cs3 = os.path.join(BUILDS_DIR, iname + ".cs3")
        if os.path.exists(cs3):
            new_hash = sha256_hash(cs3)
            new_size = file_size(cs3)
            new_ver  = read_version_from_gradle(iname)
            changed  = False
            if p.get("fileHash") != new_hash or p.get("fileSize") != new_size:
                p["fileHash"] = new_hash; p["fileSize"] = new_size; changed = True
            if p.get("version") != new_ver:
                p["version"] = new_ver; changed = True
            if changed:
                updated += 1
            p["url"] = RAW_BASE + iname + ".cs3"
            p["repositoryUrl"] = REPO_URL
    print(f"[sync] Updated {updated} entries dari builds aktual")

    added = 0
    for e in EXTRA_PLUGINS:
        iname = e["internalName"]
        if iname in existing:
            continue
        cs3 = os.path.join(BUILDS_DIR, iname + ".cs3")
        if not os.path.exists(cs3):
            print(f"[sync] SKIP (no cs3): {iname}"); continue
        ver = read_version_from_gradle(iname)
        entry = {"iconUrl":e["iconUrl"],"fileHash":sha256_hash(cs3),"apiVersion":1,"repositoryUrl":REPO_URL,"fileSize":file_size(cs3),"status":e["status"],"language":e["language"],"authors":e["authors"],"tvTypes":e["tvTypes"],"version":ver,"internalName":iname,"description":e["description"],"url":RAW_BASE+iname+".cs3","name":e["name"]}
        plugins.append(entry)
        print(f"[sync] Added: {iname} (v{ver})")
        added += 1

    print(f"[sync] Total: {len(plugins)} plugin ({added} baru)")
    out = json.dumps(plugins, indent=4, ensure_ascii=False)
    for path in [ROOT_JSON, BUILDS_JSON]:
        with open(path,"w",encoding="utf-8",newline="\n") as f:
            f.write(out)
    check = json.loads(out)
    first_bytes = open(ROOT_JSON,"rb").read(3)
    print(f"[sync] JSON valid: {len(check)} plugins")
    if first_bytes[0] == 0xEF:
        print("[sync] ERROR: BOM!"); sys.exit(1)
    print("[sync] DONE")

if __name__ == "__main__":
    main()
