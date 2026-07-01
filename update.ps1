$files = Get-ChildItem -Path D:\WEB\4\TESTINGCF -Filter LicenseClient.kt -Recurse
foreach ($file in $files) {
    $content = Get-Content $file.FullName -Raw
    $content = $content -replace 'private const val PREF_NAME = "cs_premium"', 'private var PREF_NAME = "cs_premium"'
    
    $search = 'fun init\(context: Context, pluginName: String = "plugin"\) \{\s*appContext = context\.applicationContext'
    $replace = "fun init(context: Context, pluginName: String = `"plugin`") {`n        PREF_NAME = `"cs_premium_`$pluginName`".replace(Regex(`"[^A-Za-z0-9]`"), `"`")`n        appContext = context.applicationContext"
    
    $content = $content -replace $search, $replace
    Set-Content -Path $file.FullName -Value $content
}
Write-Output "Updated $(($files | Measure-Object).Count) files."
