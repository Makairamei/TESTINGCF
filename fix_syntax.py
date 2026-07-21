import os
import glob

plugins = [
    "Anichin", "AnimeSailProvider", "AnimexinProvider", "AnixCafeProvider",
    "DonghuaFilm", "Donghuastream", "Donghub", "KuronimeProvider", "Cinemax21Provider",
    "DailymotionProvider", "DracinSI", "DrakorKita", "DramaIdProvider", "Dutamovie"
]

count = 0
for p in plugins:
    pattern = os.path.join(p, "src", "main", "kotlin", "com", "**", "*LicenseClient.kt")
    files = glob.glob(pattern, recursive=True)
    if files:
        file = files[0]
        with open(file, 'r', encoding='utf-8') as f:
            content = f.read()
        
        # Find the line and replace it
        bad_line1 = 'val response = app.get("$SERVER_URL/api/discover?device_id=$deviceId&plugin_name=${pluginName.replace("`"", "")}").text'
        bad_line2 = 'val response = app.get("$SERVER_URL/api/discover?device_id=$deviceId&plugin_name=${pluginName.replace(\'`"\', \'\')}").text'
        
        # Actually let's just use regex to replace the response line
        import re
        content = re.sub(
            r'val response = app\.get\("\$SERVER_URL/api/discover\?device_id=\$deviceId&plugin_name=.*?"\)\.text',
            'val cleanPlugin = pluginName.replace("\\"", "")\n            val response = app.get("$SERVER_URL/api/discover?device_id=$deviceId&plugin_name=$cleanPlugin").text',
            content
        )
        
        with open(file, 'w', encoding='utf-8') as f:
            f.write(content)
        count += 1

print(f"Updated {count} files")
