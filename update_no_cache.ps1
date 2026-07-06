$plugins = @(
    "Anichin", "AnimeChina", "AnimeSailProvider", "AnimexinProvider", "AnixCafeProvider",
    "DonghuaFilm", "Donghuastream", "Donghub", "KuronimeProvider", "Cinemax21Provider",
    "DailymotionProvider", "DracinSI", "DrakorKita", "DramaIdProvider", "Dutamovie"
)

$count = 0
foreach ($p in $plugins) {
    # Find the LicenseClient.kt file
    $file = Get-ChildItem -Path ".\$p\src\main\kotlin\com" -Filter "*LicenseClient.kt" -Recurse | Select-Object -First 1
    if ($file) {
        $content = Get-Content $file.FullName -Raw
        
        # 1. Update discoverKey signature and remove setLicenseKey call
        $content = $content -replace 'private suspend fun discoverKey\(\): String\? \{', 'private suspend fun discoverKey(pluginName: String): String? {'
        $content = $content -replace 'app\.get\("\$SERVER_URL/api/discover\?device_id=\$deviceId"\)', 'app.get("$SERVER_URL/api/discover?device_id=$deviceId&plugin_name=${pluginName.replace("`"", "")}")'
        $content = $content -replace 'appContext\?\.let \{ setLicenseKey\(it, json\.key\) \}', '// caching removed'
        
        # 2. Update checkLicense to call discoverKey(pluginName)
        $content = $content -replace 'if \(key\.isNullOrEmpty\(\)\) key = discoverKey\(\)', 'if (key.isNullOrEmpty()) key = discoverKey(pluginName)'
        
        # 3. Modify getLicenseKey to ALWAYS return null so it NEVER uses the locked cache
        $content = $content -replace 'private fun getLicenseKey\(\): String\? \{[\s\S]*?\}', "private fun getLicenseKey(): String? {`n        return null // Force dynamic discovery to prevent locking`n    }"

        # 4. Update logActionAsync to dynamically discover key if getLicenseKey() is null (inside GlobalScope.launch)
        $content = $content -replace 'val key = getLicenseKey\(\) \?: return(\s+val deviceId = getDeviceId\(\);?\s+val deviceModel = getDeviceModel\(\)\s+GlobalScope\.launch \{\s+try \{)', "`$1`n                val key = getLicenseKey() ?: discoverKey(pluginName) ?: return@launch"

        Set-Content -Path $file.FullName -Value $content -NoNewline
        $count++
    }
}
Write-Output "Updated $count files to remove caching and pass pluginName."
