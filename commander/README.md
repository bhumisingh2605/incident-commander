# Incident Commander

**AI-powered incident investigation and controlled remediation built with Java and Spring Boot.**

Incident Commander helps investigate application incidents using service health, metrics, dependency information, and logs. It uses Spring AI and a local Ollama model to assist with root-cause analysis and supports a controlled remediation workflow with approval and verification.

## Documentation

- [Configuration Guide](../docs/CONFIGURATION.md) — Environment variables, ports, and operational limits.
- [API Reference](../docs/API.md) — API endpoints, authentication, and PowerShell examples.

## Key Features

- **AI-assisted root-cause analysis** — Investigate incidents and identify suspected failing services.
- **Investigation tools** — Gather health, metrics, dependency, and log information.
- **Root-cause guardrails** — Validate diagnosis results and support an `UNKNOWN` outcome when the cause is uncertain.
- **Remediation proposals** — Create and manage proposed operational actions.
- **Human approval workflow** — Allow authorized operators to approve or reject remediation proposals.
- **Execution safety** — Support idempotent remediation execution to help prevent duplicate actions.
- **Incident lifecycle management** — Track incident status and resolution.
- **Security** — HTTP Basic authentication with viewer and operator access.
- **Observability** — Integrate Prometheus metrics and Alertmanager alerts.
- **Persistent storage** — Use PostgreSQL with pgvector support.

## Technology Stack

| Technology | Purpose |
|---|---|
| Java 21 | Backend development |
| Spring Boot | Application framework and REST APIs |
| Spring AI | LLM integration and tool calling |
| Ollama | Local model inference |
| Qwen3 | Configured local language model |
| PostgreSQL | Persistent application data |
| pgvector | Vector storage support |
| Prometheus | Metrics collection |
| Alertmanager | Alert routing |
| Docker Compose | Local service orchestration |
| Maven | Build and dependency management |

## Architecture

```text
Application Services
       |
       v
Prometheus Metrics
       |
       v
Alertmanager
       |
       v
Incident Commander
(Spring Boot + Spring AI)
       |
       v
Investigation Tools
(Health, Metrics, Dependencies, Logs)
       |
       v
AI Root-Cause Analysis
       |
       v
Root-Cause Guardrails
       |
       v
Remediation Proposal
       |
       v
Operator Approval
       |
       v
Action Execution
       |
       v
Verification and Incident Lifecycle
```

PostgreSQL stores application state, while Prometheus and Alertmanager provide monitoring and alerting integrations.

## Incident Investigation Workflow

1. An application alert is delivered to Incident Commander.
2. The backend gathers relevant evidence through its investigation tools.
3. The AI analyst evaluates the available evidence and proposes a suspected root cause.
4. Guardrails validate the diagnosis; uncertain cases may return `UNKNOWN`.
5. The system creates a remediation proposal when appropriate.
6. An authorized operator reviews and approves or rejects the proposal.
7. Approved remediation actions are executed and verified.
8. Incident status is updated according to the workflow.

AI-generated diagnoses are not guaranteed to be correct. Remediation actions should be reviewed and executed only under the project's configured safety controls.

## Local Development Setup

### Prerequisites

- Java 21
- Docker Desktop
- Ollama
- The configured Ollama model
- Git

### 1. Clone the repository

```powershell
git clone https://github.com/bhumisingh2605/incident-commander.git
cd incident-commander
```

### 2. Configure environment variables

Configure the required environment variables using the project's existing environment setup. These include database credentials and application security credentials.

Do not commit real passwords, API keys, or other secrets to Git.

See the [Configuration Guide](../docs/CONFIGURATION.md) for configuration details.

### 3. Start infrastructure

Use the Docker Compose configuration provided by the repository to start the required database, application services, Prometheus, and Alertmanager.

Ensure Ollama is running and the configured model is available.

### 4. Build the application

From the `commander` directory, run:

```powershell
.\mvnw.cmd clean package
```

### 5. Run the application

After a successful build and with the required environment variables loaded:

```powershell
java -jar .\target\commander-0.0.1-SNAPSHOT.jar
```

Check the project's configuration for the required ports and service endpoints.

## Testing and Validation

The project includes automated tests, PostgreSQL integration tests, security checks, and scenario-based incident testing.

Previously recorded validation results:

- **Security matrix:** 153 checks passed, 0 failed.
- **Scenario history:** The latest saved result for each of 12 scenarios was marked correct.
- **Build and focused remediation tests:** Reported successful in the development environment.

The saved scenario results span multiple prompt versions, so they should not be interpreted as a single-version benchmark. Results may also vary with model behavior and available telemetry.

## Engineering Challenges

This project explores backend engineering problems that arise when integrating AI into operational workflows:

- Gathering evidence from multiple observability tools
- Handling uncertain or conflicting root-cause diagnoses
- Separating diagnosis from authorization to execute actions
- Preventing duplicate remediation execution
- Keeping proposal and incident lifecycle states consistent
- Testing security boundaries and database-backed workflows

## Future Improvements

- Benchmark all incident scenarios under a consistent prompt version
- Improve root-cause accuracy and evaluation metrics
- Expand auditability and incident timeline visualization
- Add production deployment and secrets-management hardening
- Measure investigation latency, tool usage, and remediation outcomes

## Disclaimer

Incident Commander is a development project for AI-assisted incident investigation. AI output can be incomplete or incorrect. Always review diagnosis evidence and remediation actions before executing operational changes.