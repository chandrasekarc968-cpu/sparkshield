.PHONY: help test-python test-models test-all train-model evaluate-model export-onnx quantize-model run-mock run-mock-tcp clean

PYTHON ?= python

help:
	@echo "SparkShield Build and Test Targets:"
	@echo "  make test-all       - Run complete automated test suite (Phase 1 & Phase 2)"
	@echo "  make test-python    - Run Virtual Meter Core test suite (Phase 1)"
	@echo "  make test-models    - Run ML pipeline, ONNX, and Quantization tests (Phase 2)"
	@echo "  make train-model    - Train SparkShield1DCNN classifier (1000 samples/class)"
	@echo "  make evaluate-model - Evaluate checkpoint on held-out test set and write metadata"
	@echo "  make export-onnx    - Export checkpoint to static ONNX and validate numerical parity"
	@echo "  make quantize-model - Calibrate and statically quantize ONNX model to INT8"
	@echo "  make run-mock       - Run interactive mock telemetry stream"
	@echo "  make run-mock-tcp   - Run mock telemetry TCP server on port 9002"
	@echo "  make clean          - Remove temporary build, checkpoint, and cache artifacts"

test-all:
	$(PYTHON) -m pytest python_core/tests models/tests -v

test-python:
	$(PYTHON) -m pytest python_core/tests -v

test-models:
	$(PYTHON) -m pytest models/tests -v

train-model:
	$(PYTHON) models/train.py --epochs 10 --train-samples 1000 --val-samples 200 --test-samples 200

evaluate-model:
	$(PYTHON) models/evaluate.py

export-onnx:
	$(PYTHON) models/export_onnx.py

quantize-model:
	$(PYTHON) models/quantize.py

run-mock:
	$(PYTHON) -m python_core.mock_stream --rate 10 --count 20

run-mock-tcp:
	$(PYTHON) -m python_core.mock_stream --rate 10 --tcp --port 9002

clean:
	find . -type d -name "__pycache__" -exec rm -rf {} + 2>/dev/null || true
	find . -type d -name ".pytest_cache" -exec rm -rf {} + 2>/dev/null || true
