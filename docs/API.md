\# API



Base URL: `http://localhost:8080`



The API uses HTTP Basic authentication. After loading `.env.ps1`, the `$H` header used in the examples should authenticate as the operator. Verify the authentication setup in the project before relying on these examples.



\## Roles



| Login | Intended access |

|---|---|

| Unauthenticated | Health endpoint access may be permitted; other access depends on the security configuration |

| `viewer` | Read-only access to incidents, proposals, audit data, and UI |

| `operator` | Viewer access plus investigation, proposal decisions, deployments, and debugging |

| `alertmanager` | Alert submission through `POST /api/alerts` |



Confirm the exact endpoint permissions against the current Spring Security configuration.



\## Endpoints



| Method and path | Purpose |

|---|---|

| `GET /api/me` | Return the caller's identity and roles |

| `GET /api/incidents?status=` | List incidents, optionally filtered by status |

| `GET /api/incidents/{id}` | Retrieve incident details |

| `POST /api/incidents/{id}/investigate` | Queue an investigation |

| `GET /api/incidents/{id}/postmortem` | Retrieve a text postmortem |

| `GET /api/incidents/{id}/postmortem?ai=true` | Request AI-generated prevention ideas, if supported |

| `GET /api/proposals?status=` | List proposals, optionally filtered by status |

| `POST /api/proposals/{id}/approve` | Approve a proposal |

| `POST /api/proposals/{id}/reject` | Reject a proposal |

| `POST /api/proposals/{id}/execute` | Execute an approved proposal |

| `POST /api/deployments` | Record a deployment |

| `POST /api/alerts` | Receive an alert webhook |

| `GET /api/audit/tool-calls?limit=` | Inspect recorded model tool calls |

| `GET /api/audit/events?limit=\&subject=\&id=` | Inspect recorded audit events |

| `GET /actuator/health` | Check application health |

| `GET /actuator/prometheus` | Expose application metrics |

| `GET /debug/...` | Debug endpoints, depending on the configured routes |



Check controller mappings and security rules for the exact supported parameters, response codes, and roles.



\## PowerShell examples



Load environment variables first:



```powershell

cd C:\\Projects\\incident-commander

. .\\.env.ps1

```



Use the project's configured operator header to make authenticated requests. The examples below assume `$H` has already been initialized correctly.



\### Identify the current user



```powershell

Invoke-RestMethod "http://localhost:8080/api/me" -Headers $H

```



\### List incidents awaiting approval



```powershell

Invoke-RestMethod "http://localhost:8080/api/incidents?status=AWAITING\_APPROVAL" -Headers $H

```



\### Inspect an incident



Replace `12` with an incident ID that exists in your database.



```powershell

Invoke-RestMethod "http://localhost:8080/api/incidents/12" -Headers $H

```



\### Record a deployment



```powershell

$body = @{

&#x20;   service = "payment-service"

&#x20;   version = "v2.1.0"

&#x20;   deployedBy = "operator"

&#x20;   notes = "retry logic"

} | ConvertTo-Json



Invoke-RestMethod `

&#x20;   -Method Post `

&#x20;   "http://localhost:8080/api/deployments" `

&#x20;   -Headers $H `

&#x20;   -ContentType "application/json" `

&#x20;   -Body $body

```



Use only an example deployment payload accepted by the current controller.



\## Status codes



Common HTTP statuses include:



\- `200`: request succeeded

\- `202`: request accepted for asynchronous processing

\- `400`: invalid request

\- `401`: authentication required or failed

\- `403`: authenticated but not authorized

\- `404`: resource or route not found

\- `409`: request conflicts with the current state or a business rule



Actual responses depend on the endpoint and exception handling.



\## Alert webhook



Alertmanager sends alert data to `POST /api/alerts`. The expected payload and authentication must match the application's webhook controller and Alertmanager configuration. Never publish real passwords or secret values in this document.

