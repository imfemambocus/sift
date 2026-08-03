SHELL := /usr/bin/env bash

.PHONY: help db backend stop clean

help:
	@echo "make db        start postgres (creates .env on first run)"
	@echo "make backend   run the backend against it, on http://localhost:7777"
	@echo "make stop      stop containers, keep the data"
	@echo "make clean     stop containers and delete the database volume"

# only runs when .env is absent, so a real one is never overwritten
.env:
	@sed 's|^SIFT_ENCRYPTION_KEY=.*|SIFT_ENCRYPTION_KEY='"$$(openssl rand -base64 32)"'|' .env.example > .env
	@echo "wrote .env with a freshly generated encryption key"

db: .env
	@docker compose up -d --wait db

backend: db
	@set -a && source .env && set +a && \
	SIFT_DB_URL=jdbc:postgresql://localhost:5433/$$POSTGRES_DB \
	SIFT_DB_USER=$$POSTGRES_USER \
	SIFT_DB_PASSWORD=$$POSTGRES_PASSWORD \
	backend/gradlew -p backend bootRun

stop:
	@docker compose down

clean:
	@docker compose down -v
