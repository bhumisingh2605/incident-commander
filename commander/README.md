# Incident Commander

AI-powered incident investigation and remediation using Java, Spring Boot, Spring AI, PostgreSQL/pgvector, Ollama, Docker, Prometheus, and Alertmanager.

## Features
- Incident tracking and AI root-cause analysis
- Health, metrics, dependency, and log investigation tools
- Root-cause guardrails
- Remediation proposals and approval workflow
- HTTP Basic authentication
- Prometheus metrics integration

## Build
Run: .\mvnw.cmd clean package

## Run
Load required environment variables, then run: java -jar .\target\commander-0.0.1-SNAPSHOT.jar

## Note
Root-cause analysis may return UNKNOWN. Review remediation actions before execution.
