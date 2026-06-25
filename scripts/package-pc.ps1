$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
$pcClient = Join-Path $root 'pc-client'
$releaseRoot = Join-Path $root 'release'
$packageJsonPath = Join-Path $pcClient 'package.json'
$packageJson = Get-Content -Raw -Encoding UTF8 -LiteralPath $packageJsonPath | ConvertFrom-Json
$version = $packageJson.version
$electronDist = Join-Path $pcClient 'node_modules\electron\dist'
$pcOut = Join-Path $releaseRoot "GShareScreen-PC-$version-win-x64"
$zipPath = Join-Path $releaseRoot "GShareScreen-PC-$version-win-x64-portable.zip"

Push-Location $pcClient
try {
    if (-not (Test-Path -LiteralPath 'node_modules')) {
        npm install
    }

    if (-not (Test-Path -LiteralPath 'node_modules\electron\dist\electron.exe')) {
        node .\node_modules\electron\install.js
    }
} finally {
    Pop-Location
}

if (-not (Test-Path -LiteralPath $electronDist)) {
    throw "Electron runtime not found: $electronDist"
}

New-Item -ItemType Directory -Force -Path $releaseRoot | Out-Null

if (Test-Path -LiteralPath $pcOut) {
    $resolved = (Resolve-Path -LiteralPath $pcOut).Path
    if (-not $resolved.StartsWith($releaseRoot, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to remove path outside release root: $resolved"
    }
    Remove-Item -LiteralPath $pcOut -Recurse -Force
}

New-Item -ItemType Directory -Force -Path $pcOut | Out-Null
Copy-Item -Path (Join-Path $electronDist '*') -Destination $pcOut -Recurse -Force

$electronExe = Join-Path $pcOut 'electron.exe'
$appExe = Join-Path $pcOut 'GShareScreen.exe'
if (-not (Test-Path -LiteralPath $electronExe)) {
    throw "Electron exe was not copied: $electronExe"
}
if (Test-Path -LiteralPath $appExe) {
    Remove-Item -LiteralPath $appExe -Force
}
Rename-Item -LiteralPath $electronExe -NewName 'GShareScreen.exe'

$appRoot = Join-Path $pcOut 'resources\app'
New-Item -ItemType Directory -Force -Path $appRoot | Out-Null
Copy-Item -LiteralPath (Join-Path $pcClient 'src') -Destination $appRoot -Recurse -Force
Copy-Item -LiteralPath (Join-Path $pcClient 'assets') -Destination $appRoot -Recurse -Force
Copy-Item -LiteralPath $packageJsonPath -Destination $appRoot -Force

$modulesRoot = Join-Path $appRoot 'node_modules'
New-Item -ItemType Directory -Force -Path $modulesRoot | Out-Null

$dependencies = @()
if ($packageJson.dependencies) {
    $dependencies = $packageJson.dependencies.PSObject.Properties.Name | Where-Object { $_ -ne 'electron' }
}

foreach ($dependency in $dependencies) {
    $source = Join-Path $pcClient (Join-Path 'node_modules' $dependency)
    if (-not (Test-Path -LiteralPath $source)) {
        throw "Dependency not found. Run npm install first: $dependency"
    }

    if ($dependency.StartsWith('@')) {
        $scope = Split-Path $dependency -Parent
        $scopeTarget = Join-Path $modulesRoot $scope
        New-Item -ItemType Directory -Force -Path $scopeTarget | Out-Null
        Copy-Item -LiteralPath $source -Destination $scopeTarget -Recurse -Force
    } else {
        Copy-Item -LiteralPath $source -Destination $modulesRoot -Recurse -Force
    }
}

if (Test-Path -LiteralPath $zipPath) {
    $resolvedZip = (Resolve-Path -LiteralPath $zipPath).Path
    if (-not $resolvedZip.StartsWith($releaseRoot, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to remove zip outside release root: $resolvedZip"
    }
    Remove-Item -LiteralPath $zipPath -Force
}

Compress-Archive -LiteralPath $pcOut -DestinationPath $zipPath -Force

Get-Item -LiteralPath $zipPath
