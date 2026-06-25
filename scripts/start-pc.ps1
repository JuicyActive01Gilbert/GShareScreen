$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
$pcClient = Join-Path $root 'pc-client'

Push-Location $pcClient
try {
    if (-not (Test-Path -LiteralPath 'node_modules')) {
        npm install
    }

    if (-not (Test-Path -LiteralPath 'node_modules\electron\dist\electron.exe')) {
        node .\node_modules\electron\install.js
    }

    npm start
} finally {
    Pop-Location
}
