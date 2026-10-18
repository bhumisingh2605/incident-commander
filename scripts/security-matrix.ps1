param([string]$Base = "http://localhost:8080")

foreach ($v in "COMMANDER_VIEWER_PASSWORD", "COMMANDER_OPERATOR_PASSWORD", "COMMANDER_WEBHOOK_PASSWORD") {
  if (-not (Get-Item "env:$v" -ErrorAction SilentlyContinue)) { Write-Host "Set $v first (run: . .\.env.ps1)"; exit 1 }
}

function Basic($user, $pass) {
  @{ Authorization = "Basic " + [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes("${user}:$pass")) }
}
$ids = [ordered]@{
  none     = @{}
  wrong    = Basic "operator" "definitely-wrong-password"
  viewer   = Basic "viewer" $env:COMMANDER_VIEWER_PASSWORD
  operator = Basic "operator" $env:COMMANDER_OPERATOR_PASSWORD
  webhook  = Basic "alertmanager" $env:COMMANDER_WEBHOOK_PASSWORD
}

function Status($method, $path, $headers, $body) {
  $req = @{ Uri = "$Base$path"; Method = $method; Headers = $headers; UseBasicParsing = $true; TimeoutSec = 30 }
  if ($null -ne $body) { $req.Body = $body; $req.ContentType = "application/json" }
  try { return [int](Invoke-WebRequest @req).StatusCode }
  catch { if ($_.Exception.Response) { return [int]$_.Exception.Response.StatusCode } else { return 0 } }
}

# Expected status per login. The webhook user may only post alerts, so it gets 403 elsewhere.
function Want($viewer, $operator, $webhook = 403) {
  @{ none = 401; wrong = 401; viewer = $viewer; operator = $operator; webhook = $webhook }
}

$list = Invoke-RestMethod "$Base/api/incidents" -Headers $ids.operator
$first = if ($list) { @($list)[0].id } else { $null }

$cases = @(
  @{ N = "health probe";                   M = "GET";    P = "/actuator/health";                      E = @{ none = 200; viewer = 200; operator = 200; webhook = 200 } }
  @{ N = "metrics endpoint";               M = "GET";    P = "/actuator/prometheus";                  E = (Want 403 200) }
  @{ N = "audit events";                   M = "GET";    P = "/api/audit/events?limit=5";             E = (Want 200 200) }
  @{ N = "audit events, bad subject";      M = "GET";    P = "/api/audit/events?subject=EVIL";        E = (Want 400 400) }
  @{ N = "audit events, bad limit";        M = "GET";    P = "/api/audit/events?limit=abc";           E = (Want 400 400) }
  @{ N = "whoami";                         M = "GET";    P = "/api/me";                               E = (Want 200 200) }
  @{ N = "list incidents";                 M = "GET";    P = "/api/incidents";                        E = (Want 200 200) }
  @{ N = "incident not found";             M = "GET";    P = "/api/incidents/999999";                 E = (Want 404 404) }
  @{ N = "incident, non-numeric id";       M = "GET";    P = "/api/incidents/abc";                    E = (Want 400 400) }
  @{ N = "list proposals";                 M = "GET";    P = "/api/proposals";                        E = (Want 200 200) }
  @{ N = "audit log";                      M = "GET";    P = "/api/audit/tool-calls?limit=5";         E = (Want 200 200) }
  @{ N = "audit log, bad limit";           M = "GET";    P = "/api/audit/tool-calls?limit=abc";       E = (Want 400 400) }
  @{ N = "GET on the webhook path";        M = "GET";    P = "/api/alerts";                           E = (Want 405 405) }
  @{ N = "dashboard page";                 M = "GET";    P = "/ui/index.html";                        E = (Want 200 200) }
  @{ N = "unknown path";                   M = "GET";    P = "/nothing-here";                         E = (Want 403 403) }
  @{ N = "approve, unknown id";            M = "POST";   P = "/api/proposals/999999/approve";         E = (Want 403 404) }
  @{ N = "approve, non-numeric id";        M = "POST";   P = "/api/proposals/abc/approve";            E = (Want 403 400) }
  @{ N = "reject, unknown id";             M = "POST";   P = "/api/proposals/999999/reject";          E = (Want 403 404) }
  @{ N = "execute, unknown id";            M = "POST";   P = "/api/proposals/999999/execute";         E = (Want 403 404) }
  @{ N = "re-investigate, unknown id";     M = "POST";   P = "/api/incidents/999999/investigate";     E = (Want 403 404) }
  @{ N = "delete an incident";             M = "DELETE"; P = "/api/incidents/1";                      E = (Want 403 405) }
  @{ N = "debug endpoint";                 M = "GET";    P = "/debug/health?service=payment-service"; E = (Want 403 200) }
  @{ N = "record deploy, unknown service"; M = "POST";   P = "/api/deployments"; B = '{"service":"hacker","version":"v1"}'; E = (Want 403 400) }
  @{ N = "record deploy, malformed JSON";  M = "POST";   P = "/api/deployments"; B = '{not json';           E = (Want 403 400) }
  @{ N = "webhook, empty alert list";      M = "POST";   P = "/api/alerts"; B = '{"status":"firing","alerts":[]}'; E = @{ none = 401; wrong = 401; viewer = 403; operator = 403; webhook = 200 } }
  @{ N = "webhook, malformed JSON";        M = "POST";   P = "/api/alerts"; B = '{oops';                  E = @{ none = 401; wrong = 401; viewer = 403; operator = 403; webhook = 400 } }
  @{ N = "webhook, missing alerts field";  M = "POST";   P = "/api/alerts"; B = '{"status":"firing"}';    E = @{ none = 401; wrong = 401; viewer = 403; operator = 403; webhook = 400 } }
  @{ N = "status filter with SQL text";    M = "GET";    P = "/api/incidents?status=%27%3B%20DROP%20TABLE%20incidents%3B--"; E = (Want 200 200) }
  @{ N = "list incidents after that";      M = "GET";    P = "/api/incidents";                        E = (Want 200 200) }
)
if ($first) {
  $cases += , @{ N = "postmortem";         M = "GET"; P = "/api/incidents/$first/postmortem";         E = (Want 200 200) }
  $cases += , @{ N = "postmortem with AI"; M = "GET"; P = "/api/incidents/$first/postmortem?ai=true"; E = @{ none = 401; wrong = 401; viewer = 403; webhook = 403 } }
}

$total = 0; $fail = 0; $rows = @()
foreach ($c in $cases) {
  foreach ($who in $ids.Keys) {
    if (-not $c.E.ContainsKey($who)) { continue }
    $expected = $c.E[$who]
    $got = Status $c.M $c.P $ids[$who] $c.B
    $total++
    $ok = ($got -eq $expected)
    if (-not $ok) { $fail++ }
    $rows += [pscustomobject]@{ Result = $(if ($ok) { "ok" } else { "FAIL" }); Case = $c.N; As = $who; Expect = $expected; Got = $got }
  }
}
$rows | Where-Object { $_.Result -eq "FAIL" } | Format-Table -AutoSize
"{0} checks, {1} failed" -f $total, $fail
exit $(if ($fail) { 1 } else { 0 })