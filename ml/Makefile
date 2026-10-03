.PHONY: install run test lint openapi docker

install:
	pip install -e ".[dev]"

run:
	uvicorn divvy_ml.app:app --reload --port 8000

test:
	pytest

lint:
	ruff check .

openapi:
	python -m divvy_ml.openapi

docker:
	docker build -t divvy-ml .
