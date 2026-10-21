# Incident Commander - common tasks (Linux/macOS/Git Bash). On Windows use the PowerShell scripts in scripts/.
.DEFAULT_GOAL := help
COMPOSE      := docker compose
COMPOSE_FULL := docker compose -f docker-compose.yml -f docker-compose.full.yml

.PHONY: help secrets model up up-infra down clean logs ps test demo security-matrix

help: ## Show this help
	@grep -E '^[a-zA-Z_-]+:.*?## ' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[1m%-16s\033[0m %s\n", $$1, $$2}'

.env:
	bash scripts/setup-secrets.sh

secrets: ## Generate local passwords (.env and the Alertmanager webhook password)
	bash scripts/setup-secrets.sh

model: ## Pull the Ollama model used by the analyst
	ollama pull qwen3:8b

up: .env ## Start the full stack (database, sample services, Prometheus, Alertmanager, Commander)
	$(COMPOSE_FULL) up -d --build
	@echo "Dashboard: http://localhost:8080/ui/index.html   (log in as viewer or operator; passwords are in .env)"

up-infra: .env ## Start only the infrastructure and sample services (run Commander from your IDE)
	$(COMPOSE) up -d --build

down: ## Stop everything (keeps the database volume)
	$(COMPOSE_FULL) down

clean: ## Stop everything and delete the database volume
	$(COMPOSE_FULL) down -v

logs: ## Follow Commander's logs
	$(COMPOSE_FULL) logs -f commander

ps: ## Show container status
	$(COMPOSE_FULL) ps

test: ## Run the unit and integration tests (needs Docker for Testcontainers)
	cd commander && DB_PASSWORD=$${DB_PASSWORD:-test-only-password} ./mvnw -B verify

demo: ## Break payment-service and watch Commander diagnose and fix it
	bash scripts/demo.sh

security-matrix: ## Run the role/permission matrix against a running Commander
	bash scripts/security-matrix.sh