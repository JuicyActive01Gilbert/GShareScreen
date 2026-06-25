$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
$androidApp = Join-Path $root 'android-app'

Push-Location $androidApp
try {
    .\gradlew.bat :app:assembleDebug --no-daemon
    Get-Item -LiteralPath '.\app\build\outputs\apk\debug\app-debug.apk'
} finally {
    Pop-Location
}
