# Generates local passwords. Run once in a clean clone before Docker Compose starts.
$root = Split-Path -Parent $PSScriptRoot

function New-Secret {
    $bytes = New-Object byte[] 24
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $rng.GetBytes($bytes)
    } finally {
        $rng.Dispose()
    }
    [Convert]::ToBase64String($bytes) -replace '[+/=]', ''
}

$operator = New-Secret
$viewer = New-Secret
$webhook = New-Secret

$lines = @(
    '$env:COMMANDER_VIEWER_PASSWORD = "' + $viewer + '"'
    '$env:COMMANDER_OPERATOR_PASSWORD = "' + $operator + '"'
    '$env:COMMANDER_WEBHOOK_PASSWORD = "' + $webhook + '"'
    '$global:H = @{ Authorization = "Basic " + [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes("operator:" + $env:COMMANDER_OPERATOR_PASSWORD)) }'
)

$envFile = Join-Path $root ".env.ps1"
Set-Content -Path $envFile -Value $lines -Encoding utf8

$webhookFile = Join-Path $root "infra\alertmanager\webhook_password"
Set-Content -Path $webhookFile -Value $webhook -NoNewline -Encoding ascii

Write-Host "Done. Load them in each terminal with: . .\.env.ps1"