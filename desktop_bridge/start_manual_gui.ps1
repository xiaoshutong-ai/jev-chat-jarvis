[CmdletBinding()]
param(
    [ValidatePattern('^(fixture-account|manual-[A-Za-z0-9_-]{1,113})$')]
    [string]$AccountRef = 'manual-pilot',
    [ValidateRange(1025, 65535)]
    [int]$Port = 18766
)

$ErrorActionPreference = 'Stop'
if ($PSVersionTable.PSVersion.Major -lt 7) {
    throw 'PWSH7_REQUIRED'
}

$entry = Join-Path $PSScriptRoot 'relay.py'
if (!(Test-Path -LiteralPath $entry)) {
    throw 'JEV_MANUAL_RELAY_MISSING'
}
$python = Get-Command 'python' -ErrorAction Stop
if (@(Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue).Count -ne 0) {
    throw 'JEV_LOCAL_PORT_IN_USE'
}

$oldToken = [Environment]::GetEnvironmentVariable('JEV_RELAY_TOKEN', 'Process')
try {
    $env:JEV_RELAY_TOKEN = [Convert]::ToHexString(
        [Security.Cryptography.RandomNumberGenerator]::GetBytes(32)
    ).ToLowerInvariant()
    Write-Output 'JEV_MANUAL_GUI=STARTING'
    Write-Output 'SOURCE=EXPLICIT_USER_SELECTED_CLIPBOARD_ONLY'
    Write-Output 'LOCAL_API_BIND=127.0.0.1'
    Write-Output 'MODEL_CALLS_IN_DESKTOP=NONE'
    & $python.Source $entry '--account-ref' $AccountRef '--port' "$Port" '--manual-gui'
    if ($LASTEXITCODE -ne 0) {
        throw 'JEV_MANUAL_GUI_RETURNED_ERROR'
    }
} finally {
    if ($null -eq $oldToken) {
        Remove-Item -LiteralPath 'Env:JEV_RELAY_TOKEN' -ErrorAction SilentlyContinue
    } else {
        $env:JEV_RELAY_TOKEN = $oldToken
    }
    Write-Output 'JEV_MANUAL_GUI=STOPPED'
}
