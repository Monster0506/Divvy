.PHONY: install run test lint

install:
	pip install -e ".[dev]"

run:
	uvicorn divvy_ml.app:app --reload --port 8000

test:
	pytest

lint:
	ruff check .
