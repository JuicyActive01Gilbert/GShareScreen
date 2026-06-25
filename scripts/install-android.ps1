$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
$androidApp = Join-Path $root 'android-app'
$apk = Join-Path $androidApp 'app\build\outputs\apk\debug\app-debug.apk'

function Find-Adb {
    $candidates = @(
        $env:ADB,
        $(if ($env:ANDROID_HOME) { Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe' }),
        $(if ($env:ANDROID_SDK_ROOT) { Join-Path $env:ANDROID_SDK_ROOT 'platform-tools\adb.exe' }),
        'C:\Program Files\Android\platform-tools\adb.exe'
    ) | Where-Object { $_ }

    foreach ($candidate in $candidates) {
        if (Test-Path -LiteralPath $candidate) {
            return $candidate
        }
    }

    $fromPath = Get-Command adb -ErrorAction SilentlyContinue
    if ($fromPath) {
        return $fromPath.Source
    }

    throw 'adb not found. Set ADB, ANDROID_HOME, ANDROID_SDK_ROOT, or add adb to PATH.'
}

$adb = Find-Adb

if (-not (Test-Path -LiteralPath $apk)) {
    Push-Location $androidApp
    try {
        .\gradlew.bat :app:assembleDebug --no-daemon
    } finally {
        Pop-Location
    }
}

& $adb devices
& $adb install -r $apk
& $adb shell monkey -p com.gilbert.screenshare -c android.intent.category.LAUNCHER 1
