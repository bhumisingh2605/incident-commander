param(
    [Parameter(Mandatory = $true)][string]$Id,
    [string]$Commander = "http://localhost:8080",
    [string]$Model = "qwen3:8b",
    [string]$PromptVersion = "v1",
    [string]$Endpoint = "analyze",
    [int]$CooldownSeconds = 0
)

$ErrorActionPreference = "Continue"

if (-not $env:COMMANDER_OPERATOR_PASSWORD) {
    Write-Host "Set COMMANDER_OPERATOR_PASSWORD first (run: . .\.env.ps1)"
    exit 1
}

$AuthHeader = @{
    Authorization = "Basic " + [Convert]::ToBase64String(
        [Text.Encoding]::ASCII.GetBytes(
            "operator:" + $env:COMMANDER_OPERATOR_PASSWORD
        )
    )
}

$root       = Split-Path -Parent $PSScriptRoot
$orderUrl   = "http://localhost:8081"
$paymentUrl = "http://localhost:8082"
$orderJson  = '{"item":"book","amount":499}'
$orderAlert = "HighErrorRate firing on order-service: Error rate above 5% on order-service"

$scenarios = @{
    "S1" = @{
        Name = "payment deploy, then payment errors"
        Alert = $orderAlert
        Service = "payment-service"
        Categories = @("BAD_DEPLOY", "APPLICATION_ERROR")
    }

    "S2" = @{
        Name = "payment errors, no recent deploy"
        Alert = $orderAlert
        Service = "payment-service"
        Categories = @("APPLICATION_ERROR", "DEPENDENCY_FAILURE")
    }

    "S3" = @{
        Name = "payment slow"
        Alert = "HighLatencyP95 firing on order-service: p95 latency above 1s on order-service"
        Service = "payment-service"
        Categories = @("SLOW_DEPENDENCY", "APPLICATION_ERROR")
    }

    "S4" = @{
        Name = "order-service errors, payment healthy"
        Alert = $orderAlert
        Service = "order-service"
        Categories = @("APPLICATION_ERROR")
    }

    "S5" = @{
        Name = "payment container stopped"
        Alert = $orderAlert
        Service = "payment-service"
        Categories = @("SERVICE_DOWN", "DEPENDENCY_FAILURE")
    }

    "S6" = @{
        Name = "payment memory leak"
        Alert = "HighHeapUsage firing on payment-service: heap above 100 MB on payment-service"
        Service = "payment-service"
        Categories = @("RESOURCE_EXHAUSTION")
    }

    "S7" = @{
        Name = "red herring: order deploy, payment errors"
        Alert = $orderAlert
        Service = "payment-service"
        Categories = @("APPLICATION_ERROR", "DEPENDENCY_FAILURE")
    }

    "S8" = @{
        Name = "false alarm, system healthy"
        Alert = $orderAlert
        Service = "unknown"
        Categories = @("UNKNOWN")
    }

    "S9" = @{
        Name = "order-service slow, payment healthy"
        Alert = "HighLatencyP95 firing on order-service: p95 latency above 1s on order-service"
        Service = "order-service"
        Categories = @("SLOW_DEPENDENCY", "APPLICATION_ERROR")
    }

    "S10" = @{
        Name = "order-service memory leak"
        Alert = "HighHeapUsage firing on order-service: heap above 100 MB on order-service"
        Service = "order-service"
        Categories = @("RESOURCE_EXHAUSTION")
    }

    "S11" = @{
        Name = "order-service deploy, then order errors"
        Alert = $orderAlert
        Service = "order-service"
        Categories = @("BAD_DEPLOY", "APPLICATION_ERROR")
    }

    "S12" = @{
        Name = "latency false alarm, system healthy"
        Alert = "HighLatencyP95 firing on order-service: p95 latency above 1s on order-service"
        Service = "unknown"
        Categories = @("UNKNOWN")
    }
}

if (-not $scenarios.ContainsKey($Id)) {
    Write-Host "Unknown scenario '$Id'. Use S1 to S12."
    exit 1
}

$sc = $scenarios[$Id]

# Check that the configured analysis endpoint exists.
try {
    Invoke-WebRequest `
        -Method Post `
        -Headers $AuthHeader `
        -Uri "$Commander/debug/$Endpoint" `
        -UseBasicParsing `
        -TimeoutSec 10 | Out-Null
}
catch {
    $code = 0

    if ($_.Exception.Response) {
        $code = [int]$_.Exception.Response.StatusCode
    }

    if ($code -eq 404 -or $code -eq 0) {
        Write-Host "Endpoint /debug/$Endpoint not found on $Commander (status $code). Restart the commander first."
        exit 1
    }
}

function Send-Orders([int]$Count, [int]$DelayMs) {
    1..$Count | ForEach-Object {
        try {
            Invoke-RestMethod `
                -Method Post `
                -Uri "$orderUrl/orders" `
                -ContentType "application/json" `
                -Body $orderJson `
                -TimeoutSec 30 | Out-Null
        }
        catch {}

        Start-Sleep -Milliseconds $DelayMs
    }
}

function Reset-Chaos {
    try {
        Invoke-RestMethod `
            -Method Post `
            -Uri "$paymentUrl/chaos/reset" `
            -TimeoutSec 10 | Out-Null
    }
    catch {}

    try {
        Invoke-RestMethod `
            -Method Post `
            -Uri "$orderUrl/chaos/reset" `
            -TimeoutSec 10 | Out-Null
    }
    catch {}
}

function Deploy([string]$service, [string]$version, [string]$notes) {
    $body = @{
        service    = $service
        version    = $version
        deployedBy = "scenario"
        notes      = $notes
    } | ConvertTo-Json

    Invoke-RestMethod `
        -Method Post `
        -Uri "$Commander/api/deployments" `
        -Headers $AuthHeader `
        -ContentType "application/json" `
        -Body $body `
        -TimeoutSec 30 | Out-Null
}

function Wait-Healthy {
    foreach ($url in @(
        "$orderUrl/actuator/health",
        "$paymentUrl/actuator/health"
    )) {
        $ok = $false

        for ($i = 0; $i -lt 45 -and -not $ok; $i++) {
            try {
                Invoke-RestMethod `
                    -Uri $url `
                    -TimeoutSec 3 | Out-Null

                $ok = $true
            }
            catch {
                Start-Sleep -Seconds 2
            }
        }

        if (-not $ok) {
            Write-Host "Service did not become healthy: $url"
            exit 1
        }
    }
}

Write-Host "[$Id] $($sc.Name)"

# Recreate application containers for a fresh scenario.
Push-Location $root

try {
    docker compose up -d --force-recreate order-service payment-service prometheus |
        Out-Null
}
finally {
    Pop-Location
}

Wait-Healthy

$prometheusReady = $false
for ($i = 0; $i -lt 30 -and -not $prometheusReady; $i++) {
    try {
        Invoke-RestMethod `
            "http://localhost:9090/-/ready" `
            -TimeoutSec 2 | Out-Null

        $prometheusReady = $true
    }
    catch {
        Start-Sleep -Seconds 1
    }
}

if (-not $prometheusReady) { throw "Prometheus did not become ready; scenario aborted." }

# Clear deployment history for this scenario.
docker exec commander-db psql `
    -U commander `
    -d commander `
    -c "DELETE FROM deployments;" | Out-Null

Write-Host "Baseline traffic (30 s)..."
Send-Orders 60 500

Write-Host "Applying fault..."

switch ($Id) {
    "S1" {
        Deploy "payment-service" "v2.0.0" "scenario S1"

        Invoke-RestMethod `
            -Method Post `
            -Uri "$paymentUrl/chaos/error?enabled=true" | Out-Null
    }

    "S2" {
        Invoke-RestMethod `
            -Method Post `
            -Uri "$paymentUrl/chaos/error?enabled=true" | Out-Null
    }

    "S3" {
        Invoke-RestMethod `
            -Method Post `
            -Uri "$paymentUrl/chaos/slow?ms=3000" | Out-Null
    }

    "S4" {
        Invoke-RestMethod `
            -Method Post `
            -Uri "$orderUrl/chaos/error?enabled=true" | Out-Null
    }

    "S5" {
        docker stop payment-service | Out-Null
    }

    "S6" {
        Invoke-RestMethod `
            -Method Post `
            -Uri "$paymentUrl/chaos/memory-leak?mb=120" | Out-Null
    }

    "S7" {
        Deploy "order-service" "v3.1.0" "scenario S7 red herring"

        Invoke-RestMethod `
            -Method Post `
            -Uri "$paymentUrl/chaos/error?enabled=true" | Out-Null
    }

    "S8" {
        # False alarm: do not inject a fault.
    }

    "S9" {
        Invoke-RestMethod `
            -Method Post `
            -Uri "$orderUrl/chaos/slow?ms=3000" | Out-Null
    }

    "S10" {
        Invoke-RestMethod `
            -Method Post `
            -Uri "$orderUrl/chaos/memory-leak?mb=120" | Out-Null
    }

    "S11" {
        Deploy "order-service" "v4.0.0" "scenario S11"

        Invoke-RestMethod `
            -Method Post `
            -Uri "$orderUrl/chaos/error?enabled=true" | Out-Null
    }

    "S12" {
        # Latency false alarm: do not inject a fault.
    }
}

Write-Host "Traffic under fault (30 s)..."
Send-Orders 60 500

# Continue background traffic while the analyst investigates.
$job = Start-Job -ScriptBlock {
    param($url, $body)

    1..1200 | ForEach-Object {
        try {
            Invoke-RestMethod `
                -Method Post `
                -Uri "$url/orders" `
                -ContentType "application/json" `
                -Body $body `
                -TimeoutSec 30 | Out-Null
        }
        catch {}

        Start-Sleep -Seconds 1
    }
} -ArgumentList $orderUrl, $orderJson

Write-Host "Running the analyst (this takes a few minutes)..."

$alertEnc = [uri]::EscapeDataString($sc.Alert)
$sw = [Diagnostics.Stopwatch]::StartNew()
$r = $null

try {
    # FIX: Include the operator's Basic Authentication header.
    $r = Invoke-RestMethod `
        -Uri "$Commander/debug/${Endpoint}?alert=$alertEnc" `
        -Headers $AuthHeader `
        -Method Get `
        -TimeoutSec 1800
}
catch {
    Write-Host "Analyze failed: $($_.Exception.Message)"
}
finally {
    $sw.Stop()

    # Stop background traffic and reset injected faults.
    if ($null -ne $job) {
        Stop-Job $job -ErrorAction SilentlyContinue
        Remove-Job $job -Force -ErrorAction SilentlyContinue
    }

    Reset-Chaos

    if ($Id -eq "S5") {
        docker start payment-service | Out-Null
    }
}

# Score the result.
$correct = $false
$service = "-"
$category = "-"
$conf = 0

if ($null -ne $r) {
    $rc = $r.rootCause

    $service = $rc.suspectedService
    $category = $rc.category
    $conf = $rc.confidence

    if ($Id -eq "S8" -or $Id -eq "S12") {
        $correct = ($service -eq "unknown")
    }
    else {
        $correct = `
            ($service -eq $sc.Service) -and `
            ($sc.Categories -contains $category)
    }
}

# Save the scenario result to CSV.
$row = [pscustomobject]@{
    Time             = (Get-Date -Format "yyyy-MM-dd HH:mm")
    Scenario         = $Id
    Model            = $Model
    PromptVersion     = $PromptVersion
    ExpectedService  = $sc.Service
    PredictedService = $service
    Category         = $category
    Confidence       = $conf
    ServiceCorrect   = ($service -eq $sc.Service)
    Correct          = $correct
    ToolCalls        = if ($null -ne $r) { $r.toolCalls } else { $null }
    Seconds          = [int]$sw.Elapsed.TotalSeconds
}

$row | Export-Csv `
    -Path (Join-Path $PSScriptRoot "results.csv") `
    -Append `
    -NoTypeInformation

# Save the raw analyst result.
$runsDir = Join-Path $PSScriptRoot "runs"

New-Item `
    -ItemType Directory `
    -Force `
    -Path $runsDir | Out-Null

$r | ConvertTo-Json -Depth 6 |
    Set-Content (
        Join-Path `
            $runsDir `
            "$Id-$(Get-Date -Format 'yyyyMMdd-HHmmss').json"
    )

$row | Format-List

if ($null -ne $r) {
    $r.rootCause.evidence
}

if ($CooldownSeconds -gt 0) {
    Write-Host "Cooling down $CooldownSeconds s so this scenario's data leaves the metrics window..."
    Start-Sleep -Seconds $CooldownSeconds
}