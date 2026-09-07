.PHONY: help test-python run-mock run-mock-tcp lint clean

PYTHON ?= python

help:
	@echo "SparkShield Build and Test Targets:"
	@echo "  make test-python    - Run complete Python test suite via pytest"
	@echo "  make run-mock       - Run interactive mock telemetry stream"
	@echo "  make run-mock-tcp   - Run mock telemetry TCP server on port 9002"
	@echo "  make clean          - Remove temporary build and cache artifacts"

test-python:
	$(PYTHON) -m pytest python_core/tests -v

run-mock:
	$(PYTHON) -m python_core.mock_stream --rate 10 --count 20

run-mock-tcp:
	$(PYTHON) -m python_core.mock_stream --rate 10 --tcp --port 9002

clean:
	find . -type d -name "__pycache__" -exec rm -rf {} + 2>/dev/null || true
	find . -type d -name ".pytest_cache" -exec rm -rf {} + 2>/dev/null || true
