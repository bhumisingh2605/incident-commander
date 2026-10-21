# API Reference

Base URL: `http://localhost:8080`

All endpoints use **HTTP Basic authentication**. Passwords come from the environment variables described in [CONFIGURATION.md](CONFIGURATION.md).

## Roles

| Login | Role | Access |
|---|---|---|
| *(none)* | – | `GET /actuator/health` only |
| `viewer` | `VIEWER` | Read-only: `GET /api/**` and the dashboard (`/ui/**`) |
| `operator` | `OPERATOR` | Everything a viewer can do, plus investigate, approve/reject/execute proposals, record deployments, read `/actuator/prometheus`, and use `/debug/**` when enabled |
| `alertmanager` | `WEBHOOK` | `POST /api/alerts` only |

Any path not listed below is denied (`403`). Missing or wrong credentials return `401`.

## Endpoints

### Identity and health

| Method and path | Role | Description |
|---|---|---|
| `GET /api/me` | viewer+ | Caller name and roles |
| `GET /actuator/health` | public | Liveness/readiness |
| `GET /actuator/prometheus` | operator | Prometheus metrics |

### Incidents

| Method and path | Role | Description |
|---|---|---|
| `GET /api/incidents?status=` | viewer+ | Latest 50 incidents, optionally filtered by status (case-insensitive) |
| `GET /api/incidents/{id}` | viewer+ | Incident with its alerts, agent runs and tool calls. `404` if missing |
| `POST /api/incidents/{id}/investigate` | operator | Queue a fresh AI investigation. `202` if queued, `404` if the incident is missing or already resolved |
| `GET /api/incidents/{id}/postmortem` | viewer+ | Plain-text postmortem built from recorded events |
| `GET /api/incidents/{id}/postmortem?ai=true` | operator | Same, plus AI-suggested prevention ideas (`403` for viewers) |

Incident statuses: `OPEN`, `INVESTIGATING`, `DIAGNOSED`, `AWAITING_APPROVAL`, `ESCALATED`, `RESOLVED`.

### Remediation proposals

| Method and path | Role | Description |
|---|---|---|
| `GET /api/proposals?status=` | viewer+ | Latest proposals, optionally filtered by status |
| `POST /api/proposals/{id}/approve` | operator | Approve **and immediately execute** the proposal |
| `POST /api/proposals/{id}/reject` | operator | Reject the proposal |
| `POST /api/proposals/{id}/execute` | operator | Execute an already-approved proposal |

Action types are `RESTART_SERVICE` and `ROLLBACK_DEPLOY`. The target must be a registered service.

| Status | Meaning |
|---|---|
| `200` | Decision recorded / action executed |
| `404` | Unknown proposal |
| `409` | Already decided, incident already resolved, service in cooldown, not approved, or another execution in progress |
| `500` | Execution failed |

### Deployments

| Method and path | Role | Description |
|---|---|---|
| `POST /api/deployments` | operator | Record a deployment (used as evidence by the analyst) |


{ "service": "payment-service", "version": "v2.1.0", "deployedBy": "operator", "notes": "retry logic" }


`service` must be a registered service and `version` is required (max 50 characters); otherwise `400`.

### Alert webhook

| Method and path | Role | Description |
|---|---|---|
| `POST /api/alerts` | alertmanager | Receives Alertmanager webhook payloads |

Firing alerts create (or join) an incident and start an investigation. Resolved alerts close the incident once all of its alerts have cleared, unless it was escalated. Malformed individual alerts are skipped; a body without an `alerts` field returns `400`.

### Audit

| Method and path | Role | Description |
|---|---|---|
| `GET /api/audit/tool-calls?limit=` | viewer+ | Recent model tool calls (default 20) |
| `GET /api/audit/events?limit=&subject=&id=` | viewer+ | Audit events (default 50). `subject` must be `PROPOSAL` or `INCIDENT`, otherwise `400` |

### Debug (disabled by default)

`GET /debug/analyze`, `/debug/analyze-fast`, `/debug/metrics`, `/debug/health`, `/debug/deps`, `/debug/logs` and similar are **only available when the app runs with `SPRING_PROFILES_ACTIVE=dev`**. Without it they return `404`. They exist for the scenario runner and manual tool testing.

## Examples

### curl (Linux, macOS, Git Bash)


set -a; . ./.env; set +a            # loads the generated passwords
OP="operator:$COMMANDER_OPERATOR_PASSWORD"

curl -u "$OP" http://localhost:8080/api/me
curl -u "$OP" "http://localhost:8080/api/incidents?status=AWAITING_APPROVAL"
curl -u "$OP" http://localhost:8080/api/incidents/1
curl -u "$OP" -X POST http://localhost:8080/api/proposals/1/approve

curl -u "$OP" -X POST http://localhost:8080/api/deployments \
  -H 'Content-Type: application/json' \
  -d '{"service":"payment-service","version":"v2.1.0","deployedBy":"operator","notes":"retry logic"}'


### PowerShell


. .\.env.ps1     # defines the passwords and the $H operator header

Invoke-RestMethod "http://localhost:8080/api/me" -Headers $H
Invoke-RestMethod "http://localhost:8080/api/incidents?status=AWAITING_APPROVAL" -Headers $H
Invoke-RestMethod "http://localhost:8080/api/incidents/1" -Headers $H
Invoke-RestMethod -Method Post "http://localhost:8080/api/proposals/1/approve" -Headers $H

$body = @{ service = "payment-service"; version = "v2.1.0"; deployedBy = "operator"; notes = "retry logic" } | ConvertTo-Json
Invoke-RestMethod -Method Post "http://localhost:8080/api/deployments" -Headers $H -ContentType "application/json" -Body $body
```

### Alertmanager payload (abridged)


{
  "status": "firing",
  "alerts": [
    {
      "status": "firing",
      "fingerprint": "3f9a1c2b7d4e5a60",
      "labels": { "alertname": "HighErrorRate", "application": "order-service", "severity": "critical" },
      "annotations": { "summary": "Error rate above 5% on order-service" },
      "startsAt": "2026-10-20T10:15:00Z"
    }
  ]
}
