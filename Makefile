# One command to put the backend on a fresh Ubuntu server:
#
#   make deploy HOST=203.0.113.5
#
# It installs Docker, opens the firewall for SSH and HTTPS, uploads the code, creates the secrets, starts the stack
# with Docker Compose and waits until https://<name>/health answers. HOST is remembered after the first run.
# Needs: make, ssh, scp, git, openssl, curl. On Windows run it from Git Bash or WSL.

-include deploy/local.mk

HOST     ?=
SSH_USER ?= root
SSH_KEY  ?= ~/.ssh/divvy_deploy
APP_DIR  ?= ~/divvy
# A free name that already points at HOST. Set DOMAIN=api.example.com to use your own domain instead.
DOMAIN   ?= $(subst .,-,$(HOST)).sslip.io

SSH_OPTS := -i $(SSH_KEY) -o StrictHostKeyChecking=accept-new -o ServerAliveInterval=30
SSH      := ssh $(SSH_OPTS) $(SSH_USER)@$(HOST)
COMPOSE  := cd $(APP_DIR) && docker compose

.PHONY: deploy check remember env bootstrap upload up auto-update verify ssh logs status backup restart down

deploy: check remember env bootstrap upload up verify

check:
	@test -n "$(HOST)" || { echo "Set the server address once: make deploy HOST=<ip>"; exit 1; }
	@test -f $(SSH_KEY) || { echo "No SSH key at $(SSH_KEY). Create one with: ssh-keygen -t ed25519 -f $(SSH_KEY)"; exit 1; }
	@git diff --quiet HEAD -- || echo "Note: uncommitted changes are not deployed; only the last commit is."

remember:
	@printf 'HOST = $(HOST)\nDOMAIN = $(DOMAIN)\n' > deploy/local.mk

# Generates the secrets locally the first time, so they survive redeploys and you can read them back.
env:
	@if [ ! -f deploy/prod.env ]; then \
	  sed -e "s/__POSTGRES_PASSWORD__/$$(openssl rand -hex 24)/" -e "s/__API_KEY__/$$(openssl rand -hex 24)/" \
	    deploy/prod.env.example > deploy/prod.env; \
	  echo "Created deploy/prod.env with new secrets."; \
	fi
	@if [ -n "$$QWEN_API_KEY" ]; then sed -i "s|^QWEN_API_KEY=.*|QWEN_API_KEY=$$QWEN_API_KEY|" deploy/prod.env; fi
	@grep -q '^QWEN_API_KEY=.\+' deploy/prod.env || echo "Warning: QWEN_API_KEY is empty in deploy/prod.env, so receipts will not be read."
	@sed -i '/^DIVVY_DOMAIN=/d' deploy/prod.env && echo "DIVVY_DOMAIN=$(DOMAIN)" >> deploy/prod.env

bootstrap:
	$(SSH) 'bash -s -- $(APP_DIR)' < deploy/bootstrap.sh

# Sends the last commit (tracked files only) and the secrets; the data volumes are never touched.
upload:
	git archive --format=tar HEAD | $(SSH) 'tar -x -C $(APP_DIR)'
	scp $(SSH_OPTS) deploy/prod.env $(SSH_USER)@$(HOST):$(APP_DIR)/.env
	$(SSH) 'chmod 600 $(APP_DIR)/.env'

up:
	$(SSH) '$(COMPOSE) up -d --build'

# Run this target in the server console on an already configured Git checkout.
auto-update:
	bash deploy/install-auto-update.sh

verify:
	@echo "Waiting for https://$(DOMAIN)/health (the first certificate takes a minute)..."
	@for i in $$(seq 1 60); do \
	  if curl -fsS -o /dev/null "https://$(DOMAIN)/health"; then \
	    echo ""; \
	    echo "Up. In the app, Account > Server:"; \
	    echo "  URL:     https://$(DOMAIN)"; \
	    echo "  API key: $$(grep '^API_KEY=' deploy/prod.env | cut -d= -f2)"; \
	    exit 0; \
	  fi; sleep 5; \
	done; \
	echo "Not answering yet. Check: make status   make logs"; exit 1

ssh:
	$(SSH)

status:
	$(SSH) '$(COMPOSE) ps'

logs:
	$(SSH) '$(COMPOSE) logs --tail=100 -f'

restart:
	$(SSH) '$(COMPOSE) restart'

down:
	$(SSH) '$(COMPOSE) down'

# Dumps the database to deploy/backups/ on this machine.
backup:
	@mkdir -p deploy/backups
	@U=$$(grep '^POSTGRES_USER=' deploy/prod.env | cut -d= -f2); D=$$(grep '^POSTGRES_DB=' deploy/prod.env | cut -d= -f2); \
	F=deploy/backups/divvy-$$(date +%Y%m%d-%H%M%S).sql.gz; \
	$(SSH) "$(COMPOSE) exec -T db pg_dump -U $$U $$D" | gzip > $$F && echo "Saved $$F"
