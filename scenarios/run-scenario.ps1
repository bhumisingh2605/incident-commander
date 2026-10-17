param(
    [Parameter(Mandatory = $true)][string]$Id,
    [string]$Commander = "http://localhost:8080",
    [string]$Model = "qwen3:8b",
    [string]$PromptVersion = "v1",
    [string]$Endpoint = "analyze",
    [int]$CooldownSeconds = 0
)

$ErrorActionPreference = "Stop"

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
$promUrl    = "http://localhost:9090"
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
    throw "Unknown scenario '$Id'. Use S1 to S12."
}

$sc = $scenarios[$Id]

# Check Commander health without triggering an AI analysis.
try {
    $health = Invoke-RestMethod `
        -Uri "$Commander/actuator/health" `
        -TimeoutSec 10 `
        -ErrorAction Stop

    if ($health.status -ne "UP") {
        throw "Commander health status is '$($health.status)', not UP."
    }

    Write-Host "Commander health check passed."
}
catch {
    throw "Commander health check failed at $Commander/actuator/health : $($_.Exception.Message)"
}

function Send-Orders([int]$Count, [int]$DelayMs) {
    for ($i = 1; $i -le $Count; $i++) {
        try {
            Invoke-RestMethod `
                -Method Post `
                -Uri "$orderUrl/orders" `
                -ContentType "application/json" `
                -Body $orderJson `
                -TimeoutSec 30 `
                -ErrorAction Stop | Out-Null
        }
        catch {
            Write-Verbose "Order request failed: $($_.Exception.Message)"
        }

        Start-Sleep -Milliseconds $DelayMs
    }
}

function Reset-Chaos {
    foreach ($url in @(
        "$paymentUrl/chaos/reset",
        "$orderUrl/chaos/reset"
    )) {
        try {
            Invoke-RestMethod `
                -Method Post `
                -Uri $url `
                -TimeoutSec 10 `
                -ErrorAction Stop | Out-Null
        }
        catch {
            Write-Warning "Could not reset chaos at ${url}: $($_.Exception.Message)"
        }
    }
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
        -TimeoutSec 30 `
        -ErrorAction Stop | Out-Null
}

function Wait-Healthy {
    foreach ($url in @(
        "$orderUrl/actuator/health",
        "$paymentUrl/actuator/health"
    )) {
        $ok = $false

        for ($i = 0; $i -lt 45 -and -not $ok; $i++) {
            try {
                $health = Invoke-RestMethod `
                    -Uri $url `
                    -TimeoutSec 3 `
                    -ErrorAction Stop

                if ($health.status -eq "UP") {
                    $ok = $true
                }
            }
            catch {
                Start-Sleep -Seconds 2
            }
        }

        if (-not $ok) {
            throw "Service did not become healthy: $url"
        }
    }
}

# Query application-route latency from Prometheus.
function Get-P95Latency([string]$service) {
    $query = 'histogram_quantile(0.95, sum by (le) (rate(http_server_requests_seconds_bucket{application="' +
        $service + '",uri!~"/actuator.*"}[5m])))'

    $encodedQuery = [uri]::EscapeDataString($query)
    $url = "$promUrl/api/v1/query?query=$encodedQuery"

    try {
        $response = Invoke-RestMethod `
            -Uri $url `
            -TimeoutSec 10 `
            -ErrorAction Stop

        if (
            $response.status -eq "success" -and
            $response.data.result.Count -gt 0
        ) {
            $value = [double]$response.data.result[0].value[1]

            if (
                -not [double]::IsNaN($value) -and
                -not [double]::IsInfinity($value)
            ) {
                return $value
            }
        }
    }
    catch {
        Write-Verbose "Could not query p95 for ${service}: $($_.Exception.Message)"
    }

    return $null
}

Write-Host "[$Id] $($sc.Name)"
Write-Host "Configured model label: $Model"
Write-Host "Prompt version label: $PromptVersion"
Write-Host "Analysis endpoint: /debug/$Endpoint"

$scenarioJob = $null
$analysisResult = $null
$analysisError = $null
$stopwatch = [Diagnostics.Stopwatch]::StartNew()

try {
    # Recreate application containers.
    Push-Location $root

    try {
        docker compose up -d --force-recreate order-service payment-service prometheus

        if ($LASTEXITCODE -ne 0) {
            throw "Docker Compose failed to recreate the scenario containers."
        }
    }
    finally {
        Pop-Location
    }

    Wait-Healthy

    # Wait for Prometheus.
    $prometheusReady = $false

    for ($i = 0; $i -lt 30 -and -not $prometheusReady; $i++) {
        try {
            Invoke-RestMethod `
                -Uri "$promUrl/-/ready" `
                -TimeoutSec 2 `
                -ErrorAction Stop | Out-Null

            $prometheusReady = $true
        }
        catch {
            Start-Sleep -Seconds 1
        }
    }

    if (-not $prometheusReady) {
        throw "Prometheus did not become ready; scenario aborted."
    }

    # Clear deployment history.
    docker exec commander-db psql -U commander -d commander -c "DELETE FROM deployments;"

    if ($LASTEXITCODE -ne 0) {
        throw "Could not clear deployment history."
    }

    # Baseline traffic.
    if ($Id -eq "S12") {
        Write-Host "S12: sending light baseline traffic..."
        Send-Orders 10 1000
        Write-Host "Waiting for latency metrics to be scraped..."
        Start-Sleep -Seconds 20
    }
    else {
        Write-Host "Baseline traffic..."
        Send-Orders 60 500
    }

    Write-Host "Applying scenario fault..."

    switch ($Id) {
        "S1" {
            Deploy "payment-service" "v2.0.0" "scenario S1"
            Invoke-RestMethod -Method Post -Uri "$paymentUrl/chaos/error?enabled=true" -TimeoutSec 10 -ErrorAction Stop | Out-Null
        }
        "S2" {
            Invoke-RestMethod -Method Post -Uri "$paymentUrl/chaos/error?enabled=true" -TimeoutSec 10 -ErrorAction Stop | Out-Null
        }
        "S3" {
            Invoke-RestMethod -Method Post -Uri "$paymentUrl/chaos/slow?ms=3000" -TimeoutSec 10 -ErrorAction Stop | Out-Null
        }
        "S4" {
            Invoke-RestMethod -Method Post -Uri "$orderUrl/chaos/error?enabled=true" -TimeoutSec 10 -ErrorAction Stop | Out-Null
        }
        "S5" {
            docker stop payment-service | Out-Null

            if ($LASTEXITCODE -ne 0) {
                throw "Could not stop payment-service for S5."
            }
        }
        "S6" {
            Invoke-RestMethod -Method Post -Uri "$paymentUrl/chaos/memory-leak?mb=120" -TimeoutSec 10 -ErrorAction Stop | Out-Null
        }
        "S7" {
            Deploy "order-service" "v3.1.0" "scenario S7 red herring"
            Invoke-RestMethod -Method Post -Uri "$paymentUrl/chaos/error?enabled=true" -TimeoutSec 10 -ErrorAction Stop | Out-Null
        }
        "S8" {
            # False alarm: no fault injected.
        }
        "S9" {
            Invoke-RestMethod -Method Post -Uri "$orderUrl/chaos/slow?ms=3000" -TimeoutSec 10 -ErrorAction Stop | Out-Null
        }
        "S10" {
            Invoke-RestMethod -Method Post -Uri "$orderUrl/chaos/memory-leak?mb=120" -TimeoutSec 10 -ErrorAction Stop | Out-Null
        }
        "S11" {
            Deploy "order-service" "v4.0.0" "scenario S11"
            Invoke-RestMethod -Method Post -Uri "$orderUrl/chaos/error?enabled=true" -TimeoutSec 10 -ErrorAction Stop | Out-Null
        }
        "S12" {
            # False alarm: no fault injected.
        }
    }

    # Generate traffic while the analyst investigates.
    if ($Id -ne "S12") {
        Write-Host "Traffic under fault..."
        Send-Orders 60 500

        $scenarioJob = Start-Job -ScriptBlock {
            param($url, $body)

            1..1200 | ForEach-Object {
                try {
                    Invoke-RestMethod `
                        -Method Post `
                        -Uri "$url/orders" `
                        -ContentType "application/json" `
                        -Body $body `
                        -TimeoutSec 30 `
                        -ErrorAction Stop | Out-Null
                }
                catch {}

                Start-Sleep -Seconds 1
            }
        } -ArgumentList $orderUrl, $orderJson
    }
    else {
        Write-Host "S12: skipping fault traffic and background load."
    }

    Write-Host "Running analyst; this may take several minutes..."

    $alertEnc = [uri]::EscapeDataString($sc.Alert)

    # Actual analysis request; it can take several minutes.
    $analysisResult = Invoke-RestMethod `
        -Uri "$Commander/debug/${Endpoint}?alert=$alertEnc" `
        -Headers $AuthHeader `
        -Method Get `
        -TimeoutSec 1800 `
        -ErrorAction Stop
}
catch {
    $analysisError = $_.Exception.Message
    Write-Host "Scenario $Id failed: $analysisError"
}
finally {
    if ($null -ne $scenarioJob) {
        Stop-Job $scenarioJob -ErrorAction SilentlyContinue
        Remove-Job $scenarioJob -Force -ErrorAction SilentlyContinue
    }

    # Reset chaos even if analysis failed.
    Reset-Chaos

    if ($Id -eq "S5") {
        try {
            docker start payment-service | Out-Null
        }
        catch {
            Write-Warning "Could not restart payment-service after S5: $($_.Exception.Message)"
        }
    }

    $stopwatch.Stop()
}

# Collect latency telemetry.
$orderP95 = Get-P95Latency "order-service"
$paymentP95 = Get-P95Latency "payment-service"

if ($null -ne $orderP95) {
    Write-Host ("order-service route p95: {0:N2} ms" -f ($orderP95 * 1000))
}
else {
    Write-Host "order-service route p95: no usable data in the last 5 minutes"
}

if ($null -ne $paymentP95) {
    Write-Host ("payment-service route p95: {0:N2} ms" -f ($paymentP95 * 1000))
}
else {
    Write-Host "payment-service route p95: no usable data in the last 5 minutes"
}

# Score the result.
$correct = $false
$service = "-"
$category = "-"
$conf = 0
$telemetryValid = $false
$toolCalls = $null
$analysisSeconds = [int]$stopwatch.Elapsed.TotalSeconds

if ($Id -eq "S12") {
    $telemetryValid = ($null -ne $orderP95)
}

if ($null -ne $analysisResult) {
    $rc = $analysisResult.rootCause

    if ($null -ne $rc) {
        $service = $rc.suspectedService
        $category = $rc.category
        $conf = $rc.confidence
    }

    $toolCalls = $analysisResult.toolCalls

    if ($Id -eq "S8") {
        $correct = ($service -eq "unknown" -and $category -eq "UNKNOWN")
    }
    elseif ($Id -eq "S12") {
        $correct = (
            $service -eq "unknown" -and
            $category -eq "UNKNOWN" -and
            $telemetryValid
        )
    }
    else {
        $correct = (
            $service -eq $sc.Service -and
            $sc.Categories -contains $category
        )
    }
}

# Corrected result hashtable.
$row = [pscustomobject]@{
    Time              = (Get-Date -Format "yyyy-MM-dd HH:mm")
    Scenario          = $Id
    Model             = $Model
    PromptVersion     = $PromptVersion
    ExpectedService   = $sc.Service
    PredictedService  = $service
    Category          = $category
    Confidence        = $conf
    ServiceCorrect    = ($service -eq $sc.Service)
    Correct           = $correct
    TelemetryValid    = if ($Id -eq "S12") { $telemetryValid } else { $null }
    OrderP95Ms        = if ($null -ne $orderP95) { [math]::Round($orderP95 * 1000, 2) } else { $null }
    PaymentP95Ms      = if ($null -ne $paymentP95) { [math]::Round($paymentP95 * 1000, 2) } else { $null }
    ToolCalls         = $toolCalls
    Seconds           = $analysisSeconds
    AnalysisSucceeded = ($null -ne $analysisResult)
    Error             = $analysisError
}

# Save CSV result.
$row | Export-Csv `
    -Path (Join-Path $PSScriptRoot "results.csv") `
    -Append `
    -NoTypeInformation

# Save full analysis JSON or error details.
$runsDir = Join-Path $PSScriptRoot "runs"
New-Item -ItemType Directory -Force -Path $runsDir | Out-Null

$runFile = Join-Path $runsDir "$Id-$(Get-Date -Format 'yyyyMMdd-HHmmss').json"

if ($null -ne $analysisResult) {
    $analysisResult | ConvertTo-Json -Depth 8 | Set-Content $runFile
}
else {
    [pscustomobject]@{
        scenario  = $Id
        endpoint  = $Endpoint
        error     = $analysisError
        timestamp = (Get-Date).ToString("o")
    } | ConvertTo-Json -Depth 4 | Set-Content $runFile
}

$row | Format-List

if ($null -ne $analysisResult -and $null -ne $analysisResult.rootCause) {
    $analysisResult.rootCause.evidence
}

if ($Id -eq "S12" -and -not $telemetryValid) {
    Write-Warning "S12 is not validated: usable order-service latency telemetry was missing."
}

if ($CooldownSeconds -gt 0) {
    Write-Host "Cooling down for $CooldownSeconds seconds..."
    Start-Sleep -Seconds $CooldownSeconds
}